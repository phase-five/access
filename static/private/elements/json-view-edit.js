// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, eid, makeTableDataRow, makeTableHeaderRow} from "../util/util.js";

/**
 * HTML custom element that displays the contents of a JS object, typically some entity stored or received as JSON. It
 * allows changing the values associated with each key and will update the object to reflect the changes, and use
 * callbacks to signal when changes have been made. Certain values like IDs may be masked or marked as non-user-editable.
 * Optionally, it allows adding new keys and removing keys entirely.
 * In the future it may apply a schema and validation hooks to the object being edited.
 */
class JsonViewEdit extends HTMLElement {

  /** @type {HTMLDivElement} */
  propertiesDiv;

  /**
   * The object being edited.
   * @type {{}}
   */
  object;

  /**
   * If false, the object is only displayed and can't be edited by the user. The contents can still be changed
   * programmatically and the displayed object swapped out by providing a new object in code.
   * @type {boolean}
   */
  editable;

  /**
   * If true, include a header row above the table. If false, only the key-value pairs will be shown.
   * @type {boolean}
   */
  showHeader = false;

  /**
   * If true, each property will have a button allowing it to be deleted.
   * @type {boolean}
   */
  allowAddRemoveProperty = true;

  constructor() {
    super();
  }

  refreshDisplay () {
    const table = ce("table");
    // language=HTML
    table.innerHTML = `
    <colgroup>
      <col span="1" style="width: 6ch" title="key">
      <col span="1" style="width: auto" title="value">
      <col span="1" style="width: 3ch" title="delete">
    </colgroup>`;
    if (this.showHeader) {
      let headers = ["Key", "Value"];
      if (this.allowAddRemoveProperty) {
        headers.push("Remove");
      }
      table.append(makeTableHeaderRow(...headers));
    }
    for (const [k, v] of Object.entries(this.object)) {
      let rowCellContents = [k, v];
      if (this.allowAddRemoveProperty) {
        // Add a property removal button only if that functionality is enabled.
        const button = ce("button");
        button.innerHTML = "&times;";
        button.addEventListener("click", (e) => {
          delete this.object[k];
          // Surgically remove row rather than regenerating the whole table with this.refreshDisplay();
          e.target.parentElement.parentElement.remove(); // button -> td -> tr
        });
        rowCellContents.push(button);
      }
      const tr = makeTableDataRow(...rowCellContents);
      if (k === 'status' && (v === 'modified' || v === 'unsaved')) {
        tr.className = v; // Allows visually highlighting edited rows.
      }
      table.append(tr);
    }
    // eid("name-div").innerText = this.object.name;
    this.propertiesDiv.replaceChildren(table);
  }

  /**
   * Register a JS object as the one being displayed and edited by this element.
   * @type {{}}
   */
  setObject (object) {
    // Conditional is an optimization to avoid refreshing the table when the object has not changed.
    if (object !== this.object) {
      this.object = object;
      this.refreshDisplay();
    }
  }

  connectedCallback() {
    this.propertiesDiv = ce("div");
    this.appendChild(this.propertiesDiv);
    this.setObject({ });
  }

}

customElements.define("json-view-edit", JsonViewEdit);
