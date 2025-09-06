// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce} from "../util/util.js";

/** @typedef {('GTFS'|'OSMPBF'|'GRID'|'POINT'|'NETWORK'|'GEOPACKAGE')} FileType */
/** @typedef {{fileId: string, fileType: FileType, name: string, originalName: string}} File */

class FileList extends HTMLElement {

  // Element attributes

  fileType; // Filter fetched files to only this type, specified by 'type' attribute in HTML tag (can be empty string).
  displayAs; // Specified by 'display' attribute in tag. Default for empty or unrecognized types is an unordered list.

  /**
   * Track pre-existing file IDs so we can highlight any new ones as they appear.
   * TODO limit how fast this set can change, to ensure new files stay highlighted long enough to be seen.
   * @type {?Set}
   */
  initialFileIds = null;

  // Child elements are added to the component after it's connected, not in the constructor.
  constructor() {
    super();
  }

  /**
   * Convert an array of JS objects serialized from Java FileMetadata into an HTML table.
   * @param files {[File]} each object in this array will become a row in the table
   * @param initialFileIds {Set<string>} files with these IDs will not be highlighted as new
   * @return {HTMLTableElement}
   */
  static asTable = (files, initialFileIds) => {
    const table = ce("table");
    const header = ce("thead");
    header.innerHTML = `<tr><td>Name</td><td>File Type</td></tr>`;
    const body = ce("tbody");
    for (const file of files) {
      const tdName = ce("td");
      const tdType = ce("td");
      tdName.innerText = file.name || file.originalName;
      tdType.innerText = file.fileType;
      const tr = ce("tr");
      // Without shadow DOM this could easily collide with other elements on the page.
      // tr.id = file.fileId;
      if (file.fileId) {
        if (initialFileIds && !initialFileIds.has(file.fileId)) {
          tr.className = "modified";
        }
      }
      tr.className;
      tr.appendChild(tdName);
      tr.appendChild(tdType);
      body.appendChild(tr);
    }
    table.appendChild(header);
    table.appendChild(body);
    return table;
  };

  // Convert an array of JS objects serialized from Java FileMetadata into checkboxes.
  static asCheckboxFieldset = (files) => {
    // const fset = document.createElement("fieldset");
    // const legend = document.createElement("legend");
    // legend.innerText = "Select Files";
    // fset.appendChild(legend);
    const fset = document.createElement("div");
    for (const file of files) {
      const label = document.createElement("label");
      const cbox = document.createElement("input");
      cbox.type = "checkbox";
      cbox.value = file.fileId;
      label.append(cbox, ` ${file.name} (${file.fileType})`);
      const div = document.createElement("div");
      div.appendChild(label);
      fset.appendChild(div);
    }
    return fset;
  };

  /**
   * Defined with fat-arrow syntax, otherwise 'this' turns into the button when method is called as click handler.
   * Or if not there, in some other nested function call.
   * @return {Promise<void>}
   */
  refetch = async () => {
    const response = await fetch("/files/" + this.fileType);
    const files = await response.json();
    // On the first fetch, record the set of IDs to allow highlighting ones that change later.
    if (this.initialFileIds === null) {
      this.initialFileIds = new Set(files.map(f => f.fileId));
    }
    const child = (this.displayAs === 'checkbox') ?
      FileList.asCheckboxFieldset(files) :
      FileList.asTable(files, this.initialFileIds);
    this.firstChild.replaceWith(child);
  };

  // The component lifecycle rule is that you can't add child elements until "connected".
  connectedCallback() {
    this.fileType = this.getAttribute("type") || "";
    this.displayAs = this.getAttribute("display") || "list";
    const placeholderDiv = ce("div");
    // Add refresh button
    // const button = document.createElement("button");
    // button.innerText = "Refresh Files";
    // button.onclick = this.refetch;
    // button.style.width = "100%";
    // this.append(placeholderDiv, button);
    this.append(placeholderDiv);
    this.refetch();
  }

  /**
   * Return an array containing all the values of the selected checkboxes.
   * @return {[string]}
   */
  getSelected() {
    const checked = this.querySelectorAll('input[type="checkbox"]:checked');
    return Array.from(checked).map(x => x.value);
  }

}

customElements.define("file-list", FileList);
