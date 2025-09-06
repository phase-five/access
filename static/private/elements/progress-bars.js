// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, eid, human} from "../util/util.js";
import {addSseListener} from '../util/server-sent-events.js';

/** @typedef {('begin'|'step'|'done')} ProgressEventType */
/** @typedef {{id: string, type: ProgressEventType, title: ?string, total: ?number, step: ?number}} ProgressEvent */

/**
 * @callback CompletedBarCallback
 * @param barId {string} the id of the background item that just completed (not the ID of any file it produced)
 */

class ProgressBars extends HTMLElement {

  /** @type {HTMLDivElement} */
  progressBarDiv;

  /**
   * Invoked when any progress bar reaches the COMPLETED state (typically to display results).
   * It would probably be more useful to pass the ID of the work product to the callback instead of the task ID.
   * @type {CompletedBarCallback}
   */
  onAnyCompleted;

  /** @type {Map} */
  titleForId; // String title for each progress bar ID

  constructor() {
    super();
    this.titleForId = new Map();
  }

  clearCompleted = async () => {
    this.progressBarDiv.replaceChildren();
  };

  /**
   * Every event is expected to have an id property. The remaining properties (title, total, step)
   * will be applied when they are seen.
   * @param e {ProgressEvent}
   */
  handleProgressEvent (e) {
    let barDiv = eid(e.id);
    // If no progress bar div exists yet for this item id, create and add one.
    if (!barDiv) {
      const bar = ce("progress");
      const label = ce("label");
      label.append("Message placeholder.", bar);
      barDiv = ce("div");
      barDiv.id = e.id;
      barDiv.nDots = 0;
      barDiv.append(label);
      this.progressBarDiv.append(barDiv, ce("br"));
    }
    // Grab some key elements of the existing or new progress bar div.
    const label = barDiv.firstChild;
    const labelText = label.firstChild;
    const bar = label.lastChild;
    // Retain title across events, so it doesn't have to be sent every time.
    if (e.title) {
      this.titleForId.set(e.id, e.title);
    }
    let title = this.titleForId.get(e.id);
    if (e.type === 'begin') {
      bar.value = 0;
      bar.max = 1;
      labelText.replaceWith(`${title}...`);
    }
    if (e.total) {
      bar.max = e.total;
    }
    if (e.step && bar.max > 0) {
      const valueChanged = (e.step !== bar.value);
      bar.value = e.step;
      const percent = (e.step / bar.max * 100).toFixed(1) + "%";
      if (bar.max > 10000) {
        // Include exact count to show progress on large jobs where percentage updates slowly.
        title += ` ${bar.value}/${human(bar.max)}`;
      }
      let newLabel = `${title} (${percent})`;
      if (e.secRemain) {
        newLabel += ` ETA ${Math.round(e.secRemain/60)} min`
      }
      // On very large jobs, percentage might not change. Show some sign of activity.
      if (valueChanged) {
        barDiv.nDots += 1;
        if (barDiv.nDots > 3) barDiv.nDots = 0;
        newLabel += ".".repeat(barDiv.nDots);
      }
      labelText.replaceWith(newLabel);
    }
    if (e.type === 'done') {
      labelText.replaceWith(`${title} (DONE)`);
      bar.value = bar.max;
      if (this.onAnyCompleted) this.onAnyCompleted(e.id);
    }
  }

  connectedCallback() {
    // Add title
    const title = document.createElement("h3");
    // Add area to contain progress bars below title
    title.innerText = "Progress and Messages";
    this.appendChild(title);
    const progressBarDiv = document.createElement("div");
    this.append(title, progressBarDiv);
    this.progressBarDiv = progressBarDiv;
    // Add clear button
    const button = document.createElement("button");
    button.innerText = "Clear Complete";
    button.onclick = this.clearCompleted;
    this.appendChild(button);
    // Listen for server-sent progress events. TODO dump events for existing background items on EventSource connection.
    addSseListener("progress", data => {
      const e = JSON.parse(data);
      this.handleProgressEvent(e);
    });
  }

}

customElements.define("progress-bars", ProgressBars);
