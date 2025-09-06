// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// HTML Custom elements used by this page.
// Nest imports here instead of using noisy script tags in parent HTML file.
import "../elements/nav-bar.js";
import "../elements/file-list.js";
import "../elements/progress-bars.js";
import "../elements/status-mesages.js";

import {eid, qs} from '../util/util.js'

const statusMessages = qs("status-messages");
const fileList = eid("file-list");
const deleteButton = eid("delete-button");

const deleteOne = async (id) => {
  const response = await fetch("/files/" + id, { method: "DELETE" });
  // if (!response.ok) {}
  const text = await response.text();
  statusMessages.addMessageWithHttpStatus(text, response.status);
}

deleteButton.onclick = async () => {
  const fileIds = fileList.getSelected();
  const deletions = fileIds.map(deleteOne);
  await Promise.all(deletions);
  fileList.refetch();
}
