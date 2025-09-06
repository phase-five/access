// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, option, titleCase} from "../util/util.js";

/**
 * HTML custom element for a small information icon that shows usage hints on hover (as tooltips) and further detail
 * in a popup window when clicked.
 */
class InfoButton extends HTMLElement {

  shortText;
  fullText;

  constructor() {
    super();
  }

  // Circled Information Source HTML Entity &#128712; does not show up in default font.
  // Only &#8505; and &#9432; will show up correctly on iphone and ipad.
  // &#9432; is ⓘ and &#8505; is uncircled.

  connectedCallback() {
    this.shortText = this.innerText; // Ideally we'd somehow split the short and full versions (use a detail or p tag?)
    this.fullText = this.innerText;
    this.title = this.shortText; // Convert text into tooltip.
    const button = ce("button");
    button.innerHTML = "&#9432;";  // Replace text with a single character information icon.
    // The synchronous dialog functions are alert, prompt, confirm.
    // Alert has only an OK button, prompt takes some text, and confirm has OK/Cancel buttons.
    button.addEventListener("click", () => {
      alert(this.fullText);
    });
    this.replaceChildren(button); // This will also remove the info text from the original HTML.
  }

}

customElements.define("info-button", InfoButton);
