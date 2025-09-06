// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce, csvg, eid, human, option, storagePersistElement} from "../util/util.js";
import {map, mapConfigure} from "../util/map-common.js"
import {timeColorRamps, destColorRamps} from '../util/color-ramps.js';

/**
 * HTML custom element intended to provide all controls to select raster data and colorize it.
 * Expects a single MapboxGL JS map to exist on the current page, and interacts with it via the
 * map-common module. The name of the layer to be controlled must be specified as an attribute,
 * allowing more than one of these controls for multiple layers of a single map. Layers by the
 * given names are expected to already exist on the map - this control will only alter them, not
 * add them. Maybe it should in fact add them as needed.
 *
 * NOTE this is derived from destination-select.js, which it is intended to generalize and replace.
 * It also copies and generalizes some functions from analyze.js.
 */
class RasterControl extends HTMLElement {

  // Color map will be evaluated at 1024 evenly spaced values from zero to this maxValue.
  // All pixels at or above maxValue will be displayed as the last color in this ramp.
  maxValue;
  // The raw color ramp from MapboxGL JS, to be transformed into an image on the color ramp canvas.
  colorRamp;
  // SVG element to hold the scale ticks and numbers.
  svg;
  // Canvas element on which to draw the color ramp.
  canvas;
  // The name of the map layer that should be modified by this control.
  layer;

  connectedCallback () {
    // One problem with custom elements is defining sub-elements as HTML and getting them by ID.
    // If you instantiate the custom element more than once on the page its ID will not be unique.
    // This is probably a good use for the shadow DOM. You can also just create everything in code.
    const colorSelect = ce("select");
    colorSelect.replaceChildren(
      option("Red", "red"),
      option("Red-Blue", "red-blue"),
      option("Blue", "blue"),
      option("Blue-Green", "blue-green"),
      option("Green", "green"),
      option("RGB Bands", "rgb"),
      option("Spectrum", "spectrum"),
      option("None", "none")
    );

    const interpolationSelect = ce("select");
    interpolationSelect.replaceChildren(
      option("Nearest neighbor interpolation", "nearest"),
      option("Bilinear interpolation", "linear")
    );

    const opacitySlider = ce('input');
    opacitySlider.type = 'range';
    opacitySlider.min = '0';
    opacitySlider.max = '100';
    opacitySlider.step = '1';
    opacitySlider.value = '50';

    const saturationSlider = ce('input');
    saturationSlider.type = 'range';
    saturationSlider.min = '-100';
    saturationSlider.max = '100';
    saturationSlider.step = '1';
    saturationSlider.value = '0';

    const svg = csvg('svg');
    svg.setAttribute('width', '98%');
    svg.setAttribute('viewBox', '0 0 100 5');
    this.svg = svg;

    const canvas = ce('canvas');
    canvas.setAttribute('width', '256px');
    canvas.setAttribute('height', '10px');
    canvas.style.minWidth = '98%';
    canvas.style.border = '1px solid black';
    // Maintain sharp vertical edges in color ramp when scaling, do not interpolate.
    canvas.style.imageRendering = 'pixelated';
    this.canvas = canvas;

    // Maybe the slider should be in the range 0..1 or 1..100 and map to 0..maxValueInRaster.
    // TODO logarithmic slider response, range selector, or auto-max from selected data.
    const scaleSlider = ce('input');
    scaleSlider.type = 'range';
    scaleSlider.min = '0';
    scaleSlider.max = '1000000';
    scaleSlider.step = '5000';
    scaleSlider.value = '400000';

    const layer = this.getAttribute("layer")

    colorSelect.onchange = () => {
      const ramp = destColorRamps[colorSelect.value];
      map.setPaintProperty(layer, 'raster-color', ramp);
      // This is copying a 256 * 4 byte color map (an RGBAImage). See implementation notes [block comment in analyze.js].
      this.updateColorRamp(map.style.getOwnLayer(layer).colorRamp);
      storagePersistElement(colorSelect, layer + "-color-select");
    }

    interpolationSelect.onchange = () => {
      map.setPaintProperty(layer, 'raster-resampling', interpolationSelect.value);
      storagePersistElement(interpolationSelect, layer + "-interpolation");
    }

    // Oninput triggers continuously while slider is dragged.
    // Update visual representation but don't persist each intermediate value.
    // Onchange only triggers when moving the slider is complete / drag is released.
    // Persist chosen values only when interaction completes.

    opacitySlider.oninput = () => {
      map.setPaintProperty(layer, "raster-opacity", opacitySlider.valueAsNumber / 100);
    }

    saturationSlider.oninput = () => {
      map.setPaintProperty(layer, "raster-saturation", saturationSlider.valueAsNumber / 100);
    }

    scaleSlider.oninput = () => {
      let maxValue = scaleSlider.valueAsNumber;
      if (maxValue <= 0) maxValue = 4; // Impose useful lower limit instead of zero.
      // For scaling logic, see comments on map.addLayer() call for 'results' layer in results.js.
      const mix = [(256*256*256-1)/maxValue, (256*256-1)/maxValue, (256-1)/maxValue, 0];
      map.setPaintProperty(layer, "raster-color-mix", mix);
      this.updateMaxValue(maxValue);
    }

    scaleSlider.onchange = () => {
      storagePersistElement(scaleSlider, layer + "-slider-scale");
    }

    this.append(colorSelect, interpolationSelect, opacitySlider, saturationSlider, svg, canvas, scaleSlider);
    this.layer = layer;
    this.updateMaxValue(50000);
    this.drawRampCanvas();
  }

  drawRampCanvas () {
    if (!(this.colorRamp && this.canvas.getContext)) return;
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

  drawScaleSvg () {
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

  // TODO it may be possible to remove these accessor methods as this element no longer receives updates from outside.
  // FIXME ^^^

  updateColorRamp (ramp) {
    this.colorRamp = ramp;
    this.drawRampCanvas();
  }

  updateMaxValue (max) {
    this.maxValue = max;
    this.drawScaleSvg();
  }

}

customElements.define("raster-control", RasterControl);
