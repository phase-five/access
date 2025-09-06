// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, option} from "../util/util.js";

/**
 * HTML custom element providing a dropdown box to select one route out of a specified GTFS feed
 */
class RouteSelect extends HTMLElement {

  // Instance fields.
  select; // The HTML Element for the wrapped select box.
  feedId;
  newValueCallback; //

  constructor() {
    super();
  }

  async refetch () {
    if (!this.feedId) return;
    const url = `/gtfs/${this.feedId}/routes/`;
    // Show an indicator that a fetch is taking place until the new options are fully received and prepared.
    this.select.replaceChildren(option("Loading routes...", "None"));
    this.select.disabled = true;
    const response = await fetch(url);
    const routes = await response.json();
    this.select.replaceChildren(option("Select route", "None"));
    for (const route of routes) {
      this.select.append(option(route.name, route.id));
    }
    this.select.disabled = false;
  };

  connectedCallback() {
    const select = ce("select");
    this.appendChild(select);
    this.select = select;
    // Can we make the select element onchange bubble up?
    select.onchange = () => {
      if (this.newValueCallback) {
        this.newValueCallback(this.feedId, this.select.value);
      }
    }
  }

  /**
   * @param feedId {string} The ID of the GTFS feed from which to select a route.
   */
  updateGtfsFeed (feedId) {
    this.feedId = feedId;
    this.refetch();
  }

  /**
   * @return {string} The currently selected route ID.
   */
  getSelected() {
    return this.select.value;
  }

}

customElements.define("route-select", RouteSelect);
