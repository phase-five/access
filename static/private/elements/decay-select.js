// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {eid, csvg, storageRestoreElement, storagePersistElement} from "../util/util.js";

const SQRT3 = Math.sqrt(3);
const LOG_ONE_HALF = Math.log(0.5);

// median (i.e. cutoff), stddev, and travel time should all be in the same units (seconds or minutes).
const g = (median, stddev, travelTime) => {
  return 1 + Math.exp(((travelTime - median) * Math.PI) / (stddev * SQRT3));
};

/** @return {[number]} 120 values of a logistic decay function for each minute from 0 to 119. */
const decayLogistic = (median, stddev) => {
  const vals = Array(120);
  const g0 = g(median, stddev, 0);
  for (let t = 0; t < 120; t++) {
    vals[t] = g0 / g(median, stddev, t);
  }
  return vals;
}

/**
 * The cutoff must be in the same units as the travel times (here, minutes).
 * @param {number} cutoff The half-life of the exponential decay function.
 * @return {[number]} 120 values of an exponential decay function for each minute from 0 to 119.
 */
const decayExponential = (cutoff) => {
  const vals = Array(120);
  for (let t = 0; t < 120; t++) {
    vals[t] = Math.exp(LOG_ONE_HALF / cutoff * t);
  }
  return vals;
}

/**
 * HTML custom element that
 */
class DecaySelect extends HTMLElement {

  // Instance fields.
  details;
  svg;
  funcSelect;
  widthSlider;
  cutoffMinutes;

  constructor() {
    super();
  }

  connectedCallback() {
    // FIXME If ever there were two instances of this element, the IDs would clash. Shadow DOM?
    // language=HTML
    this.innerHTML = `
      <details id="decay-details">
        <summary>Decay Function</summary>
        <svg id="decay-svg" width="98%" viewBox="0 0 120 20" style="border: 1px solid black"></svg>
        <select id="decay-func-select">
          <option value="step">Step</option>
          <option value="linear">Linear</option>
          <option value="logistic">Logistic</option>
          <option value="exponential">Exponential</option>
        </select>
        <input type="range" min="0.1" max="30" step="0.1" value="0" id="decay-width-slider">
      </details>
    `;
    // const label = ce("label");
    // const funcSelect = ce("select");
    // const paramSlider = ce("range");
    // // paramSlider.class = "slider";
    // label.append("Decay Function", funcSelect, paramSlider);
    // this.replaceChildren(label);
    // this.funcSelect = funcSelect;
    // this.paramSlider = paramSlider;
    this.svg = eid("decay-svg");
    this.funcSelect = eid("decay-func-select");
    this.widthSlider = eid("decay-width-slider");
    this.funcSelect.onchange = this.redraw;
    this.widthSlider.oninput = this.redraw;
    this.cutoffMinutes = 60;
    this.details = eid('decay-details');
    this.details.addEventListener('toggle', () => {
      this.redraw();
    });
  }

  // Arrow function for lexical capture of HTML element references.
  // TODO need to fire a new request to the backend with new parameter values.
  // This only needs to update access, not time image or density, and could hit a cache that reuses oneOriginResults.
  // With density information at sufficient temporal resolution the access indicators could even be re-calculated on the client.
  redraw = () => {
    // svg.checkVisibility() returns true even when the details element is collapsed. Use the open attribute.
    // Note that for consistency, this optimization also requires a call to redraw when the details box is opened.
    if (!this.details.hasAttribute('open')) {
      return;
    }
    const line = csvg('polyline');
    if (this.funcSelect.value === 'step') {
      const x = this.cutoffMinutes;
      line.setAttribute('points', `${x},0 ${x},20`);
    } else if (this.funcSelect.value === 'linear') {
      const x = this.cutoffMinutes;
      const halfWidth = this.widthSlider.value / 2;
      line.setAttribute('points', `${x-halfWidth},0 ${x+halfWidth},20`);
    } else {
      // The last two types both involve producing a series of points for every minute in the range.
      const values = (this.funcSelect.value === 'logistic')
        ? decayLogistic(this.cutoffMinutes, this.widthSlider.value / 2)
        : decayExponential(this.cutoffMinutes);
      const points = values.map((element, index) => {
        const y = 20 - (element * 20);
        const x = index;
        return x + ',' + y;
      }).join(' ');
      line.setAttribute('points', points);
    }
    line.style = 'fill:none; stroke:black; stroke-width:0.5';
    this.svg.replaceChildren(line);
  }

  /**
   * @param m {number}
   */
  updateCutoffMinutes (m) {
    this.cutoffMinutes = m;
    this.redraw();
  }

  /**
   * @returns {{type: string} | {type: string, widthMinutes: number} | {type: string, standardDeviationMinutes: number}}
   */
  getSelected () {
    const type = this.funcSelect.value;
    let ret = { type };
    if (name === 'linear') {
      ret.widthMinutes = Number(this.widthSlider.value);
    } else if (name === 'logistic') {
      ret.standardDeviationMinutes = this.widthSlider.value / 2.0;
    }
    return ret;
  }

}

customElements.define("decay-select", DecaySelect);
