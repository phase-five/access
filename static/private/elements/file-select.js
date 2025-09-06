// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, option} from "../util/util.js";

// TODO how to have a default value for attribute? Do attributes need to be "observed" to exist?
// Should such custom elements extend Select? It seems cleaner to compose with a nested Select element.
// TODO maybe make specific subclasses for grid and network selection, which hold and directly alter a map reference?

/**
 * HTML custom element providing a dropdown box to select one file of a specified type, such as NETWORK or GRID.
 * When a different file is chosen, the element will fire a callback with the ID and full metadata of the file.
 */
class FileSelect extends HTMLElement {

  // The HTML Element for the wrapped select box.
  select;
  // Filter fetched files to only this type (or comma-separated list of types), set using 'type' attribute in HTML tag.
  fileType;
  // Not naming this onchange until I understand how normal onchange handlers work.
  newValueCallback;
  // Retains the metadata for the files in the list so we can supply it to the value change callback.
  fileMetadata;

  constructor() {
    super();
  }

  refetch = async () => {
    const response = await fetch("/files/" + this.fileType);
    const files = await response.json();
    // Various ways to clear out an option dropdown: replaceChildren() or innerHTML = ''.
    // Note that replaceChildren takes multiple node parameters, not a collection of nodes, so use spread operator.
    this.select.replaceChildren(option("Select " + this.fileType, "NONE"));
    this.fileMetadata = {};
    for (const file of files) {
      this.select.append(option(file.name, file.fileId));
      // Option values are strings, so the corresponding file metadata is stored outside the option, in an object.
      this.fileMetadata[file.fileId] = file;
    }
  };

  connectedCallback() {
    const select = ce("select");
    // TODO option for multi-select boxes instead of checkbox file-list
    // select.setAttribute("multiple", true);
    this.fileType = this.getAttribute("type") || "network";
    this.appendChild(select);
    this.select = select;
    // When a new value is selected, invoke any supplied callback function with the ID and metadata of the new value.
    select.onchange = () => {
      if (this.newValueCallback) {
        const id = select.value;
        const metadata = this.fileMetadata[id];
        // console.log(metadata);
        this.newValueCallback(id, metadata);
      }
    }
    this.refetch();
  }

  // Return the currently selected file ID.
  getSelected() {
    return this.select.value;
  }

  getSelectedMetadata() {
    return this.fileMetadata[this.select.value];
  }

}

customElements.define("file-select", FileSelect);
