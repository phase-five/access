// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// Minimalist PNG parsing ignoring image data, only extracting text chunks and other metadata.
// Pure JS implementation with no external dependencies. Uses browser's native zlib deflate.
// This is used to retrieve geographic envelopes and supplemental JSON data structures attached to the PNG file.
// This allows a single "return value" from an HTTP remote procedure call in the form of an image/png with metadata.

// https://observablehq.com/@julesblm/typed-arrays-and-arraybuffers-too
// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/DataView
// http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html#C.Anc-text

/**
 * Read one null terminated string of 8-bit characters up to maxlen bytes long (including the terminator).
 * @param {Uint8Array} bytes Raw PNG chunk data containing a null-terminated string
 * @param {int} offset Position of the string's first byte
 * @param {int} maxlen Maximum length of the string in bytes, including the null terminator
 */
const parseNullTerminatedString = (bytes, offset, maxlen) => {
  let codes = [];
  for (let i = offset; i < offset + maxlen; i += 1) {
    const code = bytes[i]; // view.getUInt8(i); for DataView
    if (code === 0) break;
    codes.push(code);
  }
  return String.fromCharCode(...codes);
}

/**
 * Decode one PNG chunk at the given start location within the supplied DataView.
 * Return an object representing the top-level structure of the chunk, including its type, data length, and checksum.
 * @param view {DataView} View of raw PNG bytes.
 * @param start {int} The start byte within view.
 * @returns {{data: Uint8Array, crc: number, length: number, type: string}} A single PNG chunk.
 */
const parseOneChunk = (view, start) => {
  // Read the length of the data chunk (not including the 8 bytes for this header and 4 bytes for trailing CRC)
  const dataLength = view.getUint32(start, false);
  let c = start + 4;
  const typeCode = String.fromCharCode(...[
    view.getUint8(c + 0),
    view.getUint8(c + 1),
    view.getUint8(c + 2),
    view.getUint8(c + 3)
  ]);
  c += 4;
  const data = new Uint8Array(view.buffer, c, dataLength);
  c += dataLength;
  const crc = view.getUint32(c, false);
  return {
    length: dataLength,
    type: typeCode,
    data: data,
    crc: crc
  };
}

/**
 * If the supplied chunk contains text (compressed or not) extract it and return it as key-value pair.
 * Otherwise, return null. This is async because built-in decompression is a stream API that is virally async.
 * Is there any synchronous zlib access built in to javascript? Data are already available, this doesn't need async.
 * @param chunk {{data: Uint8Array, crc: number, length: number, type: string}} A single PNG chunk.
 * @returns {Promise<?{key: string, value: string}>}
 */
const extractTextFromChunk = async (chunk) => {
  if (chunk.type === 'zTXt') {
    const key = parseNullTerminatedString(chunk.data, 0, 80);
    // Key is known to be one byte per character. Add null terminator and compression type byte (which is always zero).
    const headerLength = key.length + 2;
    const compressed = chunk.data.subarray(headerLength, chunk.data.byteLength);
    const decompressionStream = new DecompressionStream("deflate");
    // ReadableStream.from() is not yet widely available, you need to construct a Blob as the source.
    const decompressed = new Blob([compressed]).stream().pipeThrough(decompressionStream);
    const blob = await new Response(decompressed).blob();
    const textBuffer = await blob.arrayBuffer();
    const text = new TextDecoder("utf-8").decode(textBuffer);
    return {
      key: key,
      value: text
    };
  } else if (chunk.type === 'tEXt') {
    const key = parseNullTerminatedString(chunk.data, 0, 80);
    // Key is known to be one byte per character. Add one byte for null terminator.
    const valueBytes = chunk.data.subarray(key.length + 1, chunk.data.byteLength);
    const text = new TextDecoder('latin1').decode(valueBytes);
    return {
      key: key,
      value: text
    };
  } else if (chunk.type === 'iTXt') {
    // International Text. UTF-8 encoded text, compressed or uncompressed, with translations.
    const key = parseNullTerminatedString(chunk.data, 0, 80);
    // Key is known to be one byte per character. Add one byte for null terminator.
    let c = key.length + 1;
    const compressed = (chunk.data[c] === 1);
    if (compressed) {
      console.log('iTXt chunk compression not yet supported.');
      return null;
    }
    // The next byte is "compression method" which is always zero, so skip it.
    c += 2;
    const languageTag = parseNullTerminatedString(chunk.data, c, 32);
    c += languageTag.length + 1;
    // This should rightfully be decoded as UTF-8 but for now we're discarding the result and just need its length.
    // PNGJ library appears to reverse the meaning of language tag and translated key, but it's unmaintained.
    // https://github.com/leonbloy/pngj
    // TODO parseNullTerminatedUtf8 method.
    const translatedKey = parseNullTerminatedString(chunk.data, c, 80);
    c += translatedKey.length + 1;
    const utfBytes = chunk.data.subarray(c, chunk.data.byteLength);
    const text = new TextDecoder('utf-8').decode(utfBytes);
    return {
      key: key,
      value: text
    };
  }
  return null;
}

/**
 * @param s {string} Try to parse this string as a JSON array or object.
 * @returns { string | array | object } The array or object if parsing succeeds, otherwise return the string s.
 */
const decodeJsonValue = (s) => {
  if (s.startsWith('[') || s.startsWith("{")) {
    try {
      return JSON.parse(s);
    } catch (e) { /*fall through */ }
  }
  return s;
}

/**
 * Decode all the chunks of a PNG file, returning an object with key-value pairs from all tEXt, iTXt, and zTXt chunks.
 * Async due to virally asynchronous streaming zlib deflation farther down the stack.
 * @param buf {ArrayBuffer}
 * @returns {Promise<{}>}
 */
export const decodePngChunks = async (buf) => {
  const view = new DataView(buf);
  const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];
  for (let i = 0; i < PNG_SIGNATURE.length; i++) {
    if (view.getUint8(i) != PNG_SIGNATURE[i]) {
      throw new SyntaxError("File does not have a valid PNG header.")
    }
  }
  let chunkStart = PNG_SIGNATURE.length;
  let pngMeta = {};
  while (chunkStart < view.byteLength) {
    const chunk = parseOneChunk(view, chunkStart);
    let kv = await extractTextFromChunk(chunk);
    if (kv) {
      kv.value = decodeJsonValue(kv.value);
      pngMeta[kv.key] = kv.value;
    }
    // Chunk overhead is 12 bytes:
    // 4-byte length and 4-bytes chunk type at the beginning, plus 4-byte CRC at the end.
    chunkStart += chunk.length + 12;
  }
  return pngMeta;
}
