// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, csvg, human} from "../util/util.js";

/**
 * HTML custom element intended to provide all controls to select raster data and colorize it.
 * Currently this ONLY draws the color ramp and its numerical scale.
 * It does not include elements for selecting the data set to be displayed, the color ramp or scale.
 * It must be connected to a color ramp selector element and scale slider in other JS code.
 * Future goals:
 * Clicking the color ramp should change color ramp and communicate that to the map display.
 * It should also have interpolation (linear or nearest neighbor) settings.
 *
 * NOTE THIS IS DEPRECATED AND SHOULD BE REPLACED WITH THE RASTER-CONTROL CUSTOM ELEMENT.
 */
class DestinationSelect extends HTMLElement {

  // Data affecting color ramp and legend display.
  maxValue;
  scale;
  colorRamp;

  // SVG and Canvas elements to draw on.
  svg;
  canvas;

  connectedCallback () {
    const svg = csvg('svg');
    svg.setAttribute('width', '98%');
    svg.setAttribute('viewBox', '0 0 100 5');
    this.svg = svg;

    const canvas = ce('canvas');
    canvas.setAttribute('width', '256px');
    canvas.setAttribute('height', '10px');
    canvas.style.minWidth = '98%';
    canvas.style.border = '1px solid black';
    canvas.style.imageRendering = 'pixelated'; // Maintain sharp vertical edges when scaling
    this.canvas = canvas;

    this.append(svg, canvas);

    this.scale = 1;
    this.updateMaxValue(50000);
    this.drawRampCanvas();
  }

  drawRampCanvas () {
    if (!(this.scale && this.colorRamp)) {
      return;
    }
    if (this.canvas.getContext) {
      const ctx = this.canvas.getContext('2d');
      const width = 256;
      const height = 10;
      const imageData = ctx.getImageData(0, 0, width, height);
      const outData = imageData.data;
      let outByte = 0;
      // Can colorRamp byte array can be copied natively?
      // This is 1024 8-bit unsigned ints representing 256 RGBA colors.
      const inData = this.colorRamp.data;
      for (let r = 0; r < height; r++) {
        for (let b = 0; b < width * 4; b++) {
          outData[outByte++] = inData[b];
        }
      }
      ctx.putImageData(imageData, 0, 0);
    }
  }

  drawScaleSvg () {
    // Only render if we have all the needed numbers to form a scale.
    if (!(this.scale)) {
      return;
    }
    // First, empty the SVG of all child nodes.
    this.svg.replaceChildren();
    const nTicks = 5;
    const nRegions = nTicks - 1;
    for (let t = 0; t < nTicks; t += 1) {
      const tickMark = csvg('polyline');
      const x = t * 100 / nRegions;
      tickMark.setAttribute('points', `${x},${4} ${x},${5}`);
      tickMark.style = 'fill:none; stroke:black; stroke-width:0.5';
      this.svg.appendChild(tickMark);
      const label = csvg('text');
      label.setAttribute('x', x);
      label.setAttribute('y', 3);
      label.setAttribute("fill", "black");
      label.setAttribute("font-size", 3);
      let anchor = (t == 0) ? "start" : ((t < nRegions) ? "middle" : "end");
      label.setAttribute("text-anchor", anchor);
      label.innerHTML = human(t * this.maxValue / nRegions);
      this.svg.appendChild(label);
    }
  }

  updateColorRamp (ramp) {
    this.colorRamp = ramp;
    this.drawRampCanvas();
  }

  updateMaxValue (max) {
    this.maxValue = max;
    this.drawScaleSvg();
  }

}

customElements.define("destination-select", DestinationSelect);
