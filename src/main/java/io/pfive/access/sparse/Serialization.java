// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.sparse;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ExternalizableSerializer;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import gnu.trove.impl.hash.TPrimitiveHash;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.filepool.FileMetadata;
import io.pfive.access.store.FileWithMetadata;
import io.pfive.access.store.JsonStore;
import org.objenesis.strategy.SerializingInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;

import static io.pfive.access.util.RandomId.createRandomStringId;

/// Saves and loads sparse grids of street connector, egress, and opportunity density tiles as
/// streams of bytes, typically for storing in files. This could be reused for other types.
public abstract class Serialization {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Kryo instance is not threadsafe, create one instance of this class per thread or call.
    private static Kryo kryoWithDefaults () {
        Kryo kryo = new Kryo();
        kryo.setReferences(false);
        kryo.setRegistrationRequired(false);
        // Tell Kryo to use Trove's `externalizable` implementations for all primitive-keyed maps.
        kryo.addDefaultSerializer(TPrimitiveHash.class, ExternalizableSerializer.class);
        // When deserializing objects, try to use a zero-arg constructor if one exists.
        // Then fall back on some less efficient reflection approaches which require the class to be serializable.
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new SerializingInstantiatorStrategy()));
        return kryo;
    }

    public static void write (OutputStream outputStream, Object object) {
        Kryo kryo = kryoWithDefaults();
        Output kryoOut = new Output(outputStream);
        kryo.writeClassAndObject(kryoOut, object);
        kryoOut.flush();
    }

    public static void write (File file, Object object) {
        try (OutputStream out = new FileOutputStream(file)) {
            write(out, object);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static File writeToTempFile (String id, Object object) {
        try {
            File tempFile = File.createTempFile(id, ".kryo");
            write(tempFile, object);
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// Serialize the supplied object to disk, move it into storage under the given user.
    /// Returns the ID of the newly stored object. This does not gzip the file as it's writen out.
    /// Compressing the output is much slower but could be done in other threads.
    /// Connectors for all of France are about 830M and compress down to 360MB.
    public static String writeToStore (Object object, UserIdentity user) {
        String storeId = createRandomStringId();
        File tempFile = writeToTempFile(storeId, object);
        JsonStore.storeFile(tempFile.toPath(), storeId, user);
        return storeId;
    }

    public static Object read (InputStream inputStream) {
        Kryo kryo = kryoWithDefaults();
        Input kryoIn = new Input(inputStream);
        return kryo.readClassAndObject(kryoIn);
    }

    public static Object read (File file) {
        try (InputStream in = new FileInputStream(file)) {
            return read(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object read (FileWithMetadata file) {
        FileMetadata md = file.fileMetadata();
        LOG.info("Reading {} with ID {} and name {}.", md.fileType, md.fileId, md.name);
        return read(file.path().toFile());
    }

    public static <T> T read (FileWithMetadata file, Class<T> klass) {
        return klass.cast(read(file.path().toFile()));
    }

}
