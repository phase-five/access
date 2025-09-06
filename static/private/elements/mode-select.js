// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

/**
 * HTML custom element that allow selecting modes of transportation with checkboxes.
 * This appears as a Fieldset whose title is the text enclosed by this element.
 * If type attribute of the tag is 'transit' the checkboxes will be for transit modes, otherwise street modes.
 *
 * Usage:
 * <script type="module" src="../components/mode-select.js"></script>
 * <mode-select type="transit" id="transit-mode-select">Transit Modes</mode-select>
 * const streetModeSelect = document.getElementById('street-mode-select');
 * const streetModes = streetModeSelect.getSelected();
 */
class ModeSelect extends HTMLElement {

  // Instance fields.
  type;
  fieldset;
  checkboxes = [];

  constructor() {
    super();
  }

  addCheckbox(labelText, value, checked) {
    const label = document.createElement("label");
    const cbox = document.createElement("input");
    cbox.type = "checkbox";
    cbox.value = value;
    cbox.checked = checked;
    label.append(cbox, labelText);
    const div = document.createElement("div");
    div.appendChild(label);
    this.fieldset.appendChild(label);
    this.checkboxes.push(cbox);
  }

  connectedCallback() {
    this.type = this.getAttribute("type");
    // Create the fieldset before adding any checkboxes to it.
    const fset = document.createElement("fieldset");
    const legend = document.createElement("legend");
    legend.innerText = this.innerText;
    fset.appendChild(legend);
    this.fieldset = fset;
    if (this.type === "transit") {
      this.addCheckbox("Rail", "RAIL,SUBWAY,TRAM", true),
      this.addCheckbox("Bus", "BUS", true),
      this.addCheckbox("Other Transit", "FERRY,CABLE_CAR,GONDOLA,FUNICULAR,AIR,TRANSIT", true)
    } else {
      this.addCheckbox("Walk", "WALK", true),
      this.addCheckbox("Bike", "BICYCLE", false)
      // Do not allow CAR access/egress for now - it triggers very slow computations.
      // this.addCheckbox("Car", "CAR", false)
    }
    this.replaceChildren(fset);
  }

  /**
   * @returns {string} Comma-separated transit or street mode enum values from the selected checkboxes.
   */
  getSelected() {
    return this.checkboxes.filter(c => c.checked).map(c => c.value).join(",");
  }

}

customElements.define("mode-select", ModeSelect);
