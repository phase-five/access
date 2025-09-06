// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce} from "../util/util.js";

/**
 * This HTML custom element provides a "save" button that tracks and displays the number of changes made, and is
 * enabled only when there are unsaved changes. It clearly indicates visually whether the data are unedited, unsaved,
 * or fully saved. It will attempt to block navigating away from the page when there are any unsaved changes.
 *
 * Usage:
 * <script type="module" src="../components/save-button.js"></script>
 * <save-button url="/api/jsonstore/"></save-button>
 * const saveButton = document.querySelector('save-button');
 * saveButton.createDataCallback = () => {};
 * saveButton.changesMade("Updated item A");
 */
class SaveButton extends HTMLElement {

  /** @typedef {{action:("CREATED"|"UPDATED"), id: string}} SaveSuccessResult */

  /**
   * @callback SaveSuccessCallback will be run only if all PUT/POST operations succeed.
   * @param results {[SaveSuccessResult]} one result for each supplied entity.
   */

  /**
   * The save button will call the supplied function to retrieve entities that need to be saved.
   * Currently this is called only when the save button is clicked, but in the future it could be called for autosave.
   * Each object in the array 'data' will be sent by HTTP PUT or POST depending on whether its id property is non-null.
   * WARNING: Side effects. An id field may be created or set on the returned objects if they don't already have one.
   * @callback CreateSaveDataCallback
   * @return {{data: [{id: ?string}], success: SaveSuccessCallback}}
   */

  /** @type {CreateSaveDataCallback} */
  createDataCallback;

  /**
   * The URL path (under the same origin that served the current page) where the item will be saved. If no ID is known,
   * a save will POST the item to this URL and retain the returned ID, otherwise it will PUT to the existing ID as a
   * subpath under this URL. To ensure the string ends in a slash, it should be specified in HTML with the url property.
   * @type {string}
   */
  url = "/";

  /**
   * Tracks the changes made since the last save operation.
   * Functionally, the most important distinction is between the set being empty or holding one or more elements.
   * @type {Set<string>}
   */
  unsavedChanges = new Set();

  /**
   *  This button triggers the save process when clicked. It will be disabled until there are changes to be saved.
   *  @type {HTMLButtonElement}
   */
  button;

  /**
   * Using text and color, shows whether there are unsaved changes. This also has a tooltip (title) showing a short
   * summary of the changes that will be saved, and clicking displays that list in a modal dialog.
   * @type {HTMLDivElement}
   */
  statusArea;

  constructor() {
    super();
  }

  // TODO USE CSS CLASSES and the same colors and classes used for unsaved/modified table rows.
  static UNSAVED_COLOR = "rgb(237,208,208)";
  static NEUTRAL_COLOR = 'transparent'; // "rgb(239,239,239)";
  static ALL_SAVED_COLOR = "rgb(202,218,202)";

  // The statusX methods set the color and text of the element and enable or disable the button depending on whether
  // there are outstanding changes and whether there are save operations in progress.

  statusNoChanges () {
    this.style.backgroundColor = SaveButton.NEUTRAL_COLOR;
    this.statusArea.innerText = "No changes";
    this.button.disabled = true;
  }

  statusHasChanges () {
    this.style.backgroundColor = SaveButton.UNSAVED_COLOR;
    this.statusArea.innerText = `Unsaved changes (${this.unsavedChanges.size})`;
    this.button.disabled = false;
  }

  statusSaving () {
    this.style.backgroundColor = SaveButton.NEUTRAL_COLOR;
    this.statusArea.innerText = "Saving...";
    this.button.disabled = true;
  }

  statusSaved () {
    this.style.backgroundColor = SaveButton.ALL_SAVED_COLOR;
    // TODO "All changes saved to 1234ABCD..." with link to JSON
    this.statusArea.innerText = "All changes saved";
    this.button.disabled = true;
  }

  statusFailed () {
    this.style.backgroundColor = SaveButton.UNSAVED_COLOR;
    this.statusArea.innerText = "FAILED to save changes";
    this.button.disabled = false;
  }

  /**
   * Returns a promise for asynchronously uploading one JS object as JSON to the specified URL via POST or PUT.
   * Objects with a defined, non-null id field will be PUT to url/id. Otherwise we POST to url and receive a new ID.
   * WARNING: Method with a side effect on its parameter. May set or create an id field on the supplied object.
   * The preferred approach is to record the new ID in the SaveSuccessCallback instead of via this side effect.
   * @param data {{id: ?string}} object to save as JSON. Its id field will be created/overwritten if null or undefined.
   * @return {Promise<SaveSuccessResult>}
   */
  saveOneObject = async (data) => {
    // TODO factor out fetch with JSON body and content-type header (also used twice in derive.js)
    const put = Boolean(data.id);
    const url = put ? this.url + data.id : this.url;
    const params = {
      method: put ? "PUT" : "POST",
      body: JSON.stringify(data),
      headers: { "Content-Type": "application/json" }
    };
    // Note: Fetch promise is fulfilled even for non-success HTTP response codes. Only network failures reject.
    const response = await fetch(url, params);
    if (!response.ok) {
      throw new Error(response.statusText);
    }
    const json = await response.json();
    if (data.id !== json.id) {
      if (put) {
        throw new Error(`Id of item being edited ${data.id} is not equal to ID in PUT response ${json.id}.`);
      } else {
        data.id = json.id; // POST response contains a new ID.
      }
    }
    return json;
    // No rejection handlers - any errors will yield a rejected promise.
  }

  /** The method called by clicking the save button. Show that saving is in progress, and PUT/POST items to backend. */
  saveData () {
    this.statusSaving();
    // The createDataCallback supplies an array of the data items to save, as well as a function to call upon success.
    const {data, success: onSuccess} = this.createDataCallback();
    const uploads = data.map(this.saveOneObject);
    Promise.all(uploads).then(results => {
      // All promises fulfilled.
      this.statusSaved();
      this.unsavedChanges.clear();
      onSuccess(results);
    }).catch(e => {
      // At least one promise rejected.
      console.log(e);
      this.statusFailed();
    });
  }

  /** Resets the save button component to its initial state, showing no changes and with the button disabled. */
  reset () {
    this.unsavedChanges.clear();
    this.statusNoChanges();
  }

  /** For small, simple elements like this one we don't need a shadow DOM for styling, just style the element itself. */
  applyStyles () {
    this.style.border = "1px solid black";
    this.style.width = "96%";
    this.style.padding = "2px";
    this.style.display = "flex";
    this.style.flexFlow = "row nowrap";
    this.style.alignItems = "center";
    this.statusArea.style.paddingLeft = "1em"
    this.button.style.backgroundColor = "transparent";
    this.button.style.width = "6ch";
    this.button.innerText = "Save"; // 💾
  }

  /** Custom element setup lifecycle method. Styles, children, and event callbacks should be added here. */
  connectedCallback () {
    this.button = ce("button");
    this.statusArea = ce("div");
    this.replaceChildren(this.button, this.statusArea);
    this.applyStyles();
    this.reset();

    // Ensure url ends with a slash to facilitate adding ID to end of POST paths.
    const url = this.getAttribute("url");
    this.url = url?.endsWith("/") ? url : url + "/";

    this.button.onclick = () => { this.saveData() };

    window.addEventListener("beforeunload", (event) => {
      if (this.unsavedChanges.size > 0) {
        event.preventDefault(); // Show dialog to verify whether the user really wants to navigate away.
      }
    });

    this.statusArea.addEventListener("click", () => {
      if (this.unsavedChanges.size > 0) {
        alert("Unsaved Changes:\n" + this.unsavedChangesToText());
      }
    });
  }

  /** Combine all unsaved change descriptions in the set for display as a tooltip or in an alert dialog box. */
  unsavedChangesToText () {
    return Array.from(this.unsavedChanges).join("\n");
  }

  /**
   * A method that will be called whenever a change is made that needs to be saved later.
   * If an element is changed more than once, the same description should be supplied each time to limit the set size.
   * Effectively this is more of an interface than a callback, but referring to it as a callback works for now.
   * @callback ChangesMadeCallback
   * @param description {string} a very short description of which element has been changed and needs to be saved
   */

  /**
   * Defined as fat arrow function so 'this' will be bound correctly when this method is referenced as a callback.
   * @type {ChangesMadeCallback}
   */
  changesMade = (description) => {
    const nChanges = this.unsavedChanges.size;
    this.unsavedChanges.add(description);
    if (this.unsavedChanges.size > nChanges) {
      // Setting title will create a tooltip when hovering over the status area.
      // TODO limit size of title text
      this.statusArea.title = this.unsavedChangesToText();
    }
    this.statusHasChanges();
  }

}

// Register the custom element to make it available in HTML pages.
customElements.define("save-button", SaveButton);
