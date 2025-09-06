// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// HTML Custom elements used by this page.
// Nest imports here instead of using noisy script tags in parent HTML file.
import "../elements/nav-bar.js";
import "../elements/progress-bars.js";
import "../elements/status-mesages.js";
import "../elements/file-list.js";

// Remember not to view this page with IntelliJ's Open In... Browser.
// It would then be trying to hit the API endpoints on the wrong port.

import {eid, qs} from '../util/util.js'

const fileInput = eid("file-input");
const uploadButton = eid("upload-button");
const progressBars = qs("progress-bars");
const fileList = qs("file-list");
const statusMessages = qs("status-messages");

// Headers should be ASCII, and fetch will fail if non-ASCII characters are present. URL-encoding
// also avoids parsing problems with filenames that contain equals, semicolon, whitespace etc.
// See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Disposition#syntax on UTF8.
// Server should use filename only for display purposes, avoiding risks like filesystem traversal.

async function uploadOne (file) {
  const uploadTarget = "/upload/";
  const fetchParameters = {
    method: "POST",
    body: file,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Disposition": "attachment; filename*=UTF-8''" + encodeURIComponent(file.name)
    }
  };
  const response = await fetch(uploadTarget, fetchParameters);
  // Note that processing is not complete at this point, it's just submitted for background processing.
  if (!response.ok) {
    const text = await response.text();
    statusMessages.addMessageWithHttpStatus(text, response.status);
  }
}

// FIXME browser seems to be able to upload only 4 files at a time per tab. Is this a browser-imposed limitation?
uploadButton.onclick = async function () {
  // for...in gives array indexes. for...of gives elements.
  // Start all N uploads at once without awaiting
  let uploadPromises = [];
  for (const file of fileInput.files) {
    uploadOne(file);
  }
};

// FIXME new file is un-highlighted due to double-fetch, due to double-completion, due to "storing" step.
//   We should a) throttle creation of the existing files Set; b) distinguish between full task and sub-task completion.
progressBars.onAnyCompleted = () => {
  fileList.refetch();
}
