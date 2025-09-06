// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, option} from "../util/util.js";

/**
 * HTML custom element providing a dropdown box to select one pattern out of a specified GTFS feed, allowing inspection
 * of the details of that pattern (stops and shapes and summary statistics on timetables - departure time histograms,
 * hop times etc.) When a pattern is chosen, the element should fire a callback to update other visual elements.
 * It may contain its own collapsible or hideable rendering of the pattern details.
 */
class PatternSelect extends HTMLElement {

  // Instance fields.
  select; // The HTML Element for the wrapped select box.
  feedId;
  routeId;
  patterns;
  newValueCallback; //

  constructor() {
    super();
  }

  async refetch () {
    if (!this.feedId) return;
    if (!this.routeId) return;
    const url = `/gtfs/${this.feedId}/patterns/${this.routeId}`;
    this.select.replaceChildren(option("Loading patterns...", "None"));
    this.select.disabled = true;
    const response = await fetch(url);
    const patterns = await response.json();
    this.select.replaceChildren(option("Select pattern", "None"));
    this.patterns = {};
    for (const pattern of patterns) {
      const option = ce("option");
      option.innerText = pattern.name;
      option.value = pattern.exemplarTripId;
      this.select.append(option);
      this.patterns[pattern.exemplarTripId] = pattern;
    }
    this.select.disabled = false;
  };

  connectedCallback() {
    const select = ce("select");
    this.appendChild(select);
    this.select = select;
    // When a new value is selected, invoke any supplied callback function with the ID of the new value.
    // TODO FETCH details of newly selected pattern allowing more fine-grained analysis of active hours and days
    select.onchange = () => {
      if (this.newValueCallback) {
        this.newValueCallback(this.feedId, this.patterns[this.select.value]);
      }
    }
  }

  updateGtfsFeedAndRoute (feedId, routeId) {
    this.feedId = feedId;
    this.routeId = routeId;
    this.refetch();
  }

  clear () {
    this.feedId = null;
    this.routeId = null;
    this.select.replaceChildren();
    this.select.disabled = true;
  }

  // Return the currently selected pattern ID. Actually this is a representative ("exemplar") trip ID.
  getSelected() {
    return this.select.value;
  }

}

customElements.define("pattern-select", PatternSelect);
