// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, eid, csvg, human} from '../util/util.js'
import {testAccess, testDensity, testDual} from '../util/test-values.js'

/**
 * Interpolate a 1D array of values at the given percentile, between min/mean/max arrays.
 * @param vals {[[number]]} A 2D array with axis order (percentiles, minutes) containing three percentiles (min/mean/max).
 * @param pctIndex {number} A fractional index in the range 0...2 representing the index into min/avg/max.
 */
const interpolatePercentile = (vals, pctIndex) => {
  const whole = Math.floor(pctIndex);
  const frac = pctIndex - whole;
  if (whole === 2) return vals[2];
  const a0 = vals[whole];
  const a1 = vals[whole + 1];
  if (a0.length != a1.length) return null;
  let result = new Array(a0.length);
  for (let i = 0; i < a1.length; i++) {
    result[i] = a0[i] + frac * (a1[i] - a0[i]);
  }
  return result;
};

/**
 * Create a new TD element with the given text and add it to the given table row or header.
 * @param tr {HTMLTableRowElement}
 * @param text {string}
 */
const addTableData = (tr, text) => {
  const td = ce('td');
  td.innerText = text;
  tr.appendChild(td);
}

/**
 * Make a single SVG polyline element representing a cumulative opportunities curve.
 * @param values {[number]}
 * @param maxVal {number}
 * @param color {string}
 * @param width {number}
 * @returns {SVGElement}
 */
const makeCumulativeOpportunitiesSvgPolyline = (values, yScale, color, width) => {
  // Note that SVG axis orientation has y values increasing downward, so you have to flip the graph vertically.
  // Alternative enumeration approach: for (let [i, v] of p.entries()) { }
  const polyline = csvg("polyline");
  const points = values.map((element, index) => {
    const y = 100 - (element * yScale);
    const x = index * 2;
    return x + ',' + y;
  }).join(' ');
  polyline.setAttribute('points', points);
  polyline.style = `fill:none; stroke:${color}; stroke-width:${width}`;
  return polyline;
}

const drawMinuteTicksSvg = (svg) => {
  for (let t = 15; t < 120; t += 15) {
    const tickMark = csvg('polyline');
    const x = t * 2;
    tickMark.setAttribute('points', `${x},${7} ${x},${12}`);
    tickMark.style = 'fill:none; stroke:gray; stroke-width:1';
    svg.appendChild(tickMark);
    const label = csvg('text');
    label.setAttribute('x', x);
    label.setAttribute('y', 6);
    label.setAttribute("fill", "black");
    label.setAttribute("font-size", 8);
    label.setAttribute("text-anchor", "middle");
    label.innerHTML = t.toString();
    svg.appendChild(label);
  }
};

class AccessDisplay extends HTMLElement {

  // Data to be represented as charts and tables.
  // Opportunity density and cumulative access numbers are 3D arrays with axis order (destLayer, percentile, minutes).
  density;
  access;
  dual;
  cutoffMinutes;
  percentile;
  colorRamp;
  showDual;

  // SVG, Canvas, and text elements to draw on.
  cumulativeAccessSvg;
  densityCanvas;
  timeLegendCanvas;
  timeTable;

  connectedCallback() {
    const cumulativeAccessSvg = csvg('svg');
    cumulativeAccessSvg.setAttribute('width', '98%');
    cumulativeAccessSvg.setAttribute('viewBox', '0 0 240 100');
    cumulativeAccessSvg.style.border = '1px solid black'
    this.cumulativeAccessSvg = cumulativeAccessSvg;

    const densityCanvas = ce('canvas');
    densityCanvas.setAttribute('width', '120px');
    densityCanvas.setAttribute('height', '20px');
    // Attribute preserveAspectRatio = 'none' doesn't seem to work, maybe it's only for SVG.
    densityCanvas.style.minWidth = '98%';
    densityCanvas.style.border = '1px solid black'
    this.densityCanvas = densityCanvas;

    const minuteTicksSvg = csvg('svg');
    minuteTicksSvg.setAttribute('width', '98%');
    minuteTicksSvg.setAttribute('viewBox', '0 0 240 12');
    drawMinuteTicksSvg(minuteTicksSvg);

    const timeLegendCanvas = ce('canvas');
    timeLegendCanvas.setAttribute('width', '120px');
    timeLegendCanvas.setAttribute('height', '5px');
    timeLegendCanvas.style.minWidth = '98%';
    timeLegendCanvas.style.border = '1px solid black';
    // Turn off interpolation to get crisp vertical edges. Still jumps a bit due to insufficient pixels.
    // In any case the resolution will need to be higher to draw numbers... but those can be on a fixed SVG.
    timeLegendCanvas.style.imageRendering = 'pixelated';
    this.timeLegendCanvas = timeLegendCanvas;

    const timeTable = ce('table');
    // width together with fixed table-layout guarantees equal width columns
    timeTable.style.width = '98%';
    timeTable.style.tableLayout = 'fixed';
    // timeTable.style.minWidth = '98%';
    timeTable.style.border = '1px solid black';
    this.timeTable = timeTable;

    this.append(cumulativeAccessSvg, densityCanvas, minuteTicksSvg, timeLegendCanvas, timeTable);
    this.showDual = false; // Initially, show the primal accessibility table.

    this.updateDensity(testDensity);
    this.updateAccess(testAccess);
    this.updateDual(testDual);

    // Change display style of sub-elements when clicked.
    timeTable.onclick = () => {
      this.showDual = !this.showDual;
      this.updateTable();
    }
  }

  // On programmatic SVG: https://www.motiontricks.com/creating-dynamic-svg-elements-with-javascript/

  // Actually to achieve interpolation maybe this should be a canvas where we determine where each pixel is on a
  // 3x120 grid and set the color from the source data.

  // Tried horizontal and vertical magnification of selected row and line. Besides not looking great, this also causes
  // values to shift so is not practical for reading charts. Narrow line or glow highlighting is better.

  // TODO this could be done as a 120x3 image and scaled by the browser.
  // Unfortunately everything I try attempts to preserve the aspect ratio.
  // It looks like canvas drawImage would allow resizing and stretching to arbitrary aspect ratio:
  // https://developer.mozilla.org/en-US/docs/Web/API/Canvas_API/Tutorial/Pixel_manipulation_with_canvas#zooming_and_anti-aliasing
  // Indicators of chosen time and percentile can be a transparent vector line overlay.

  drawDensityCanvas () {
    // For some reason, the getContext function doesn't exist at some moments in execution.
    if (this.densityCanvas.getContext) {
      const ctx = this.densityCanvas.getContext('2d');
      const rowsPerPercentile = 10;
      const nPercentileBands = 2;
      const minuteToHighlight = Math.round(this.cutoffMinutes);
      const nMinutes = 120;
      const width = nMinutes;
      const height = rowsPerPercentile * nPercentileBands;
      const maxDensity = Math.max(...this.density[0][0]) // * 0.9; // Clamp at 90% to increase contrast
      const imageData = ctx.getImageData(0, 0, width, height);
      const d = imageData.data;
      let didHighlightPercentile = false;
      // Iterate over percentile indexes (excluding last one).
      let byte = 0;
      for (let p = 0; p < nPercentileBands; p++) {
        const lo = this.density[0][p];
        const hi = this.density[0][p+1];
        // Render one horizontal band between two adjacent percentiles.
        for (let r = 0; r < rowsPerPercentile; r++) {
          const frac = r/rowsPerPercentile;
          const highlightPercentile = (!didHighlightPercentile) && ((p + frac) >= this.percentile);
          if (highlightPercentile) {
            didHighlightPercentile = true;
          }
          // Render one pixel per minute in the horizontal direction.
          for (let m = 0; m < nMinutes; m++) {
            const highlightMinute = (m === minuteToHighlight);
            const interpolatedCount = lo[m] + frac * (hi[m] - lo[m]);
            const normalizedCount = interpolatedCount / maxDensity;
            let v = Math.round(normalizedCount * 255);
            const highlightAmount = (highlightPercentile || highlightMinute) ? 50 : 0;
            v += highlightAmount;
            d[byte++] = v;
            d[byte++] = v;
            d[byte++] = v + highlightAmount;
            d[byte++] = 255;
          }
        }
      }
      ctx.putImageData(imageData, 0, 0);
    }
  }

  drawAccessSvg () {
    if (!this.access) {
      return;
    }
    // Highest access values should always be in lowest travel time percentiles (row zero). First destination set only.
    const maxAccess = Math.max(...this.access[0][0]);
    const accessScale = 100 / maxAccess;
    // this.cumulativeAccessSvg.innerHTML = ``;
    this.cumulativeAccessSvg.replaceChildren();
    for (let p of this.access[0]) {
      // p will all accessibility values for each minute for a given percentile.
      // Add one line for each of these percentiles.
      const polyline = makeCumulativeOpportunitiesSvgPolyline(p, accessScale, "blue", 1);
      this.cumulativeAccessSvg.appendChild(polyline);
    }
    if (this.percentile) {
      const values = interpolatePercentile(this.access[0], this.percentile);
      const polyline = makeCumulativeOpportunitiesSvgPolyline(values, accessScale, "black", 1.5);
      this.cumulativeAccessSvg.appendChild(polyline);
    }
    if (this.cutoffMinutes) {
      const cutLine = csvg('polyline');
      const x = this.cutoffMinutes * 2;
      cutLine.setAttribute('points', `${x},${0} ${x},${120}`);
      cutLine.style = 'fill:none; stroke:gray; stroke-width:1';
      this.cumulativeAccessSvg.appendChild(cutLine);
    }
  }

  drawTimeLegend () {
    if (!(this.cutoffMinutes && this.colorRamp)) {
      return;
    }
    if (this.timeLegendCanvas.getContext) {
      const ctx = this.timeLegendCanvas.getContext('2d');
      const minute = Math.round(this.cutoffMinutes);
      const nRows = 5;
      const nMinutes = 120;
      const width = nMinutes;
      const height = nRows;
      const imageData = ctx.getImageData(0, 0, width, height);
      const d = imageData.data;
      let byte = 0;
      // Maybe the colorRamp byte array can be drawn as an image or scaled and copied natively?
      // This is 1024 8-bit unsigned ints representing 256 RGBA colors.
      const rampData = this.colorRamp.data;
      for (let r = 0; r < nRows; r++) {
        for (let m = 0; m < nMinutes; m++) {
          // Divide by two because cutoff is in middle of range. If there are only 128 values that explains color banding.
          const rampIndex = Math.min(255, Math.round(m / minute * 255 / 2));
          let rampByte = rampIndex * 4;
          d[byte++] = rampData[rampByte++];
          d[byte++] = rampData[rampByte++];
          d[byte++] = rampData[rampByte++];
          d[byte++] = rampData[rampByte++];
        }
      }
      ctx.putImageData(imageData, 0, 0);
    }
  }

  /**
   * Update the display of how opportunity densities are distributed over time.
   * We only show the first of N destination categories.
   * @param density {[[[number]]]} Array with axis order (destinations, percentiles, minutes) containing opportunity
   * densities.
   */
  updateDensity (density) {
    this.density = density;
    this.drawDensityCanvas();
  }

  /**
   * Update the cumulative accessibility chart and display accessibility figures at the selected cutoff and percentile.
   * @param access {[[[number]]]} Array with axis order (destinations, percentiles, minutes) containing cumulative
   * opportunity counts with any decay function already applied.
   */
  updateAccess (access) {
    this.access = access;
    this.drawAccessSvg();
    this.updateTable();
  }

  /**
   * Update the dual access information (i.e. minutes to reach n opportunities).
   * @param dual {[[[number]]]}
   */
  updateDual(dual) {
    this.dual = dual;
    this.updateTable();
  }

  /**
   * Maybe use get/set properties?
   * @param cutoffMinutes {number}
   */
  updateCutoffMinutes (cutoffMinutes) {
    // TODO can't we use slider.valueasnumber instead of this?
    // Annoyingly, HTML range input elements produce string values, so to be safe force all passed values to be numbers.
    this.cutoffMinutes = Number(cutoffMinutes);
    this.updateTable();
    this.drawDensityCanvas();
    // Redrawing the whole SVG is quite inefficient, we should be able to catch and alter just the single overlay line.
    this.drawAccessSvg();
    this.drawTimeLegend();
  }

  /**
   * @param percentile {number}
   */
  updatePercentile (percentile) {
    // Annoyingly, HTML range input elements produce string values, so to be safe force all passed values to be numbers.
    this.percentile = Number(percentile);
    this.updateTable();
    this.drawDensityCanvas();
    this.drawAccessSvg();
  }

  /**
   * TODO explain and specify type of ramp
   * @param ramp
   */
  updateColorRamp (ramp) {
    this.colorRamp = ramp;
    this.drawTimeLegend();
  }

  updateTable() {
    if (this.showDual) {
      this.updateDualTable();
    } else {
      this.updateAccessTable();
    }
  }

  updateAccessTable() {
    if (!(this.access && this.cutoffMinutes && this.percentile)) return;
    const minutes = [15, 30, 45, 60, 90, Math.round(this.cutoffMinutes)];
    const ps = [0, 1, 2, this.percentile];
    const table = this.timeTable;
    table.replaceChildren(); // Clear all sub-elements.
    const header = ce('thead');
    addTableData(header, "P \\ M")
    for (let m of minutes) {
      addTableData(header, Math.round(m).toString());
    }
    table.appendChild(header);
    for (let p of ps) {
      // One row per percentile
      const tr = ce('tr');
      addTableData(tr, 'P' + Math.round(p/2 * 100));
      for (let m of minutes) {
        const vals = interpolatePercentile(this.access[0], p);
        addTableData(tr, human(vals[m]));
      }
      table.appendChild(tr);
    }
  }

  updateDualTable() {
    if (!(this.dual)) return;
    const table = this.timeTable;
    table.replaceChildren(); // Clear all sub-elements.
    {
      const header = ce("thead");
      addTableData(header, "Dual");
      for (const [i, value] of this.dual[0][0].entries()) {
        addTableData(header, i + 1);
      }
      table.append(header);
    }
    for (const [p, label] of ["min", "avg", "max"].entries()) {
      // Get times to n opportunities for destination set zero and percentile p
      const times = this.dual[0][p];
      const row = ce("tr");
      addTableData(row, label);
      for (const minutes of times) {
        addTableData(row, (minutes < 0) ? "∞" : minutes);
      }
      table.append(row);
    }
  }
}

customElements.define("access-display", AccessDisplay);
