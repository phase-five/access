// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {titleCase} from "../util/util.js";

// Because this nav-bar module is imported on every page, perform browser feature detection here.
// We intentionally do not support older browsers lacking web APIs that are common as of 2023-2024.
// See also:
// https://developer.mozilla.org/en-US/docs/Web/API/HTMLScriptElement/supports_static
// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules#feature_detection
for (const feature of ["importmap", "module"]) {
  if (!HTMLScriptElement.supports?.(feature)) {
    alert("Browser does not support " + feature);
  }
}

// TODO wrap in nav-panel that applies same width and column flexbox everywhere, and nest controls inside it?
class NavBar extends HTMLElement {

  constructor() {
    super();
  }

  connectedCallback() {
    const shadow = this.attachShadow({mode: "open"});
    // language=HTML
    shadow.innerHTML = `
      <style>
        div {
            display: flex;
            flex-flow: row wrap;
            align-items: center;
        }
        div a { padding: 0.25em; }
      </style>
      <div id="navbar">
        <a href="/login.html"><img src="/logo.svg" height="32px"/></a>
        <a href="/static/pages/upload.html">Upload</a>
        <a href="/static/pages/derive.html">Derive</a>
        <a href="/static/pages/analyze.html">Analyze</a>
        <a href="/static/pages/results.html">Results</a>
      </div>
    `;
    // <a href="/static/pages/results.html">Results</a>
    // toLowerCase() is also an option.
    // localeCompare() would behave differently in different places which is not desirable.
    const highlight = titleCase(this.getAttribute("highlight"));
    for (let anchor of shadow.querySelectorAll("a")) {
      if (anchor.innerText === highlight) {
        anchor.style.backgroundColor = "#cce";
        // TODO deactivate anchor.
        //  Navigating away by linking to the same page you're on does not trigger unsaved data warning.
      }
    }
  }
}

customElements.define("nav-bar", NavBar);
