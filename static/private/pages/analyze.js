// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// HTML Custom elements used by this page. Import here, do not use script tags in parent HTML file.
import "../elements/nav-bar.js";
import "../elements/file-select.js";
import "../elements/mode-select.js";
import "../elements/access-display.js";
import "../elements/decay-select.js";
import "../elements/progress-bars.js";
import "../elements/status-mesages.js";
import "../elements/map-layers.js";
import "../elements/destination-select.js";

// Utility methods and modules used in analysis.
import {eid, qs, storagePersistElement, storageRestoreElement} from "../util/util.js";
import {decodePngChunks} from '../util/png-chunks.js'
import {timeColorRamps, destColorRamps} from '../util/color-ramps.js';
import {mapConfigure, mapSetGtfsId, mapSetOsmId, setGtfsVisible, setOsmVisible, setLayerVisible} from "../util/map-common.js";

// Initialize map. OSM and GTFS layers are always added, and invisible by default.
// Add additional analysis-specific layers here: destinations and travel time.
const map = mapConfigure((map) => {
  addRasterLayer(map, 'dest-a');
  addRasterLayer(map, 'dest-b');
  addRasterLayer(map, 'travel-time');
  // Restore saved settings as the last step after the whole map is loaded,
  // ensuring all layers are present on the map.
  restoreAllElements();
});

/**
 * Add a raster layer to the given map, with no source image or coordinates, and set to be non-visible.
 * Adding and removing the layers dynamically later (when images are known) is problematic because we want to maintain
 * layer ordering, which is established relative to other layers already in the map.
 * @param map {mapboxgl.Map}
 * @param layerId {string}
 */
const addRasterLayer = (map, layerId) => {
  // Source IDs are a separate namespace from layer IDs, so can be identical to the layer ID.
  const sourceId = layerId;
  // Set source to an empty image floating in the Atlantic near Africa.
  // When the layer is set invisible it will still validate and fetch this source, so we can't set them to null.
  map.addSource(sourceId, {
    'type': 'image',
    'url': "/static/png/blank.png",
    'coordinates': [[0, 1], [1, 1], [1, 0], [0, 0]]
  });
  map.addLayer({
    'id': layerId,
    'type': 'raster',
    'source': sourceId,
    'paint': {
      // These values should yield a fully transparent layer, until another method is called to set the color map.
      "raster-color-mix": [0, 0, 0, 0],
      "raster-color": destColorRamps['none'],
      "raster-color-range": [0, 1],
      // TODO Allow toggling interpolation method per layer
      // https://docs.mapbox.com/style-spec/reference/layers/#paint-raster-raster-resampling
      "raster-resampling": 'linear', // 'nearest' or 'linear'
      // Disable fade transition when source changes (on time, not necessarily destination density).
      // Fast fade transitions still cause a distracting flash of full transparency.
      "raster-fade-duration": 0,
    },
    'layout': { 'visibility': 'none' }
  }, "waterway");
}

/**
 * For a pre-existing raster layer of the given map, update the image URL and bounds and make it visible.
 * @param map {mapboxgl.Map}
 * @param layerId {string}
 * @param pngUrl {string}
 * @param bounds {{minLon: number, minLat: number, maxLon: number, maxLat: number}}
 */
const updateRasterLayer = (map, layerId, pngUrl, bounds) => {
  const {minLon, minLat, maxLon, maxLat} = bounds;
  const coordinates = [
    [minLon, maxLat],
    [maxLon, maxLat],
    [maxLon, minLat],
    [minLon, minLat]
  ];
  // Assuming source ID is same as layer ID.
  // It would also be possible to get the layer, then get the source for the layer.
  const source = map.getSource(layerId);
  if (source) {
    source.updateImage({
      url: pngUrl,
      "coordinates": coordinates
    });
    map.setLayoutProperty(layerId, "visibility", "visible");
  } else {
    console.log("Source layer didn't exist: ", layerId);
  }
};

const ttSlider = eid('travel-time-slider');
const percentileSlider = eid('percentile-slider');

/**
 * Update the paint properties of the travel time layer of the map so the raster-color-mix reflects the currently
 * chosen cutoff.
 */
function setTravelTimePaint() {
  if (!map.getLayer('travel-time')) console.log('No travel time layer present in map.');
  // Interpolate between three percentiles, or min/avg/max
  // Input slider value in in range [0..3]
  const p = Number(percentileSlider.value);
  const a = Math.trunc(p);
  const b = a + 1;
  const weightB = p - a;
  const weightA = 1 - weightB;
  let weights = [0, 0, 0, 0];
  // Color ramps are in the range [0..1]. We want pixels containing the selected time in minutes to evaluate to 0.5, at
  // the middle of that color ramp range. The pixel values are in minutes, but Mapbox maps them to floats in [0..1].
  // Does Mapbox map 255 => 1 or 256 => 1 ? Anecdotally the result looks better at the high end using 256.
  // Given the chosen cutoff c, find scale value x. (c/255)x = 0.5; x = 0.5/(c/255) = 0.5 * (255/c).
  let scale = 0.5 * 256.0 / ttSlider.value;
  weights[a] = weightA * scale;
  // When slider is at 2 we don't want to set element 3 of the array, which is probably alpha channel.
  if (b <= 2) {
    weights[b] = weightB * scale;
  }
  map.setPaintProperty("travel-time", "raster-color-mix", weights);
  map.setLayoutProperty("travel-time", "visibility", "visible");
}

// Note: when event handlers are defined as 'regular' function objects, 'this' will be set to the event target.
// When defining them as arrow functions with lexical 'this', you need a variable to capture target element.
// Alternatively, you can use the event handler's parameter and reference event.target.value.

const ttOutput = eid("travel-time-output");
const percentileOutput = eid("percentile-output");
const accessDisplay = qs('access-display');
const decaySelect = qs('decay-select');

ttSlider.oninput = () => {
  // Oninput triggers continuously while travel time slider is dragged.
  // Annoyingly, the range input's value is a string which can screw up math downstream if we don't convert it.
  // TODO use valueAsNumber?
  const tt = Number(ttSlider.value);
  ttOutput.value = "Minutes " + Math.round(tt);
  accessDisplay.updateCutoffMinutes(tt);
  decaySelect.updateCutoffMinutes(tt);
  setTravelTimePaint();
}

ttSlider.onchange = () => {
  // Onchange only triggers when moving the travel time slider is complete / drag is released.
  storagePersistElement(ttSlider, "travel-time");
}

percentileSlider.oninput = () => {
  // Oninput triggers continuously while percentile slider is dragged.
  // Update visual representation but don't bother persisting the value at each of these intermediate values.
  percentileOutput.value = "Percentile " + Math.round(percentileSlider.value / 2.0 * 100);
  accessDisplay.updatePercentile(percentileSlider.value);
  setTravelTimePaint();
}

percentileSlider.onchange = () => {
  // Persist the value only when interaction completes.
  // Unlike oninput, onchange only triggers when moving the percentile slider is complete / drag is released.
  storagePersistElement(percentileSlider, "percentile");
}

// Update the given mapbox source layer to use a PNG of the given grid.
// This first makes a request for the PNG file and ignores the body, getting only the bounding box header.
// Then the URL is fed to MapboxGL which will re-request it from cache to actually display the image.
// TODO decode iTXt chunks and only fall back on header if PNG does not contain the info.
// TODO change second param to url instead of gridId and merge with travel time updater function
const updateImageSource = (layerId, gridId) => {

  // Set the raster source for the grid layer on the map to the PNG version of the selected grid.
  // https://github.com/mapbox/mapbox-gl-js/pull/12352/files added the ability to change raster URLs and refresh.
  // Treating jobs as grid-one or raster-one for now.
  // We also need to set the extents from the grid metadata.

  // TODO factor out common method to extract bounds from response headers, as with travel time rasters.
  // Beware, RasterTileSource has setUrl(), but ImageSource has updateImage() to also update the bounding box.
  // See https://docs.mapbox.com/mapbox-gl-js/api/sources/#imagesource#updateimage

  // Instead of getting bounds from headers or PNG text chunks, in many cases it could come from the metadata.
  // The file-select elements generally have this metadata available.

  const gridPngUrl = '/grid/png/' + gridId;

  // DUPLICATE CODE, factor out bbox header and/or png metadata extraction.
  // Match Mapbox GL JS Accept headers to ensure cache hit.
  fetch(gridPngUrl).then(async response => {
    const bbox = response.headers.get("x-geopng-bbox");
    if (bbox) {
      const bboxFloats = bbox.split(",").map(parseFloat);
      const [minLon, minLat, maxLon, maxLat] = bboxFloats;
      const bounds = {minLon, minLat, maxLon, maxLat};
      // Pause until the full body to arrives so it'll be in cache when the map updates.
      await response.blob();
      // When map is updated and loads image, it should hit cache entry from completed fetch above.
      updateRasterLayer(map, layerId, gridPngUrl, bounds);
      // TODO make centering conditional, only if the window doesn't overlap the newly selected image at all.
      // Also, factor this out and reuse.
      // const centerLon = (minLon + maxLon)/2;
      // const centerLat = (minLat + maxLat)/2;
      // map.setCenter([centerLon, centerLat]);
    } else {
      console.log("No bounding box header present.")
    }
  });
}

// Hold on to a reference so we can use the selector value in one-to-many request.
const destSelectA = eid("dest-select-a");
destSelectA.newValueCallback = (gridId, meta) => {
  if (meta) {
    // Chosen ID corresponds to a grid file and not 'None'.
    // Note that we have three sources for bounds: x-geopng header, const wb = meta.wgsBounds, and png tEXt chunks.
    updateImageSource("dest-a", gridId);
  } else {
    setLayerVisible('dest-a', false);
  }
  storagePersistElement(destSelectA.select, 'dest-a');
}

// TODO factor out destination selector component, use two identical ones for destinations A and B.

const destSelectB = eid("dest-select-b");
destSelectB.newValueCallback = (gridId, meta) => {
  if (meta) {
    // Chosen ID corresponds to a grid file and not 'None'.
    updateImageSource("dest-b", gridId);
  } else {
    setLayerVisible('dest-b', false);
  }
  storagePersistElement(destSelectB.select, 'dest-b');
}

// TODO Scale sliders can probably be replaced with the general-purpose raster-control.js or at least reuse methods

const scaleSliderA = eid("scale-slider-a");
scaleSliderA.oninput = function () {
  let maxValue = this.valueAsNumber;
  if (maxValue <= 0) maxValue = 4;
  // For scaling logic, see comments on map.addLayer() call for 'results' layer in results.js.
  const mix = [(255*255*255)/maxValue, (255*255)/maxValue, 255/maxValue, 0];
  map.setPaintProperty("dest-a", "raster-color-mix", mix);
  eid("dest-legend-a").updateMaxValue(maxValue);
}
scaleSliderA.onchange = function () {
  storagePersistElement(scaleSliderA);
}

const scaleSliderB = eid("scale-slider-b");
scaleSliderB.oninput = function () {
  let maxValue = this.valueAsNumber;
  if (maxValue <= 0) maxValue = 4;
  // For scaling logic, see comments on map.addLayer() call for 'results' layer in results.js.
  const mix = [(255*255*255)/maxValue, (255*255)/maxValue, 255/maxValue, 0];
  map.setPaintProperty("dest-b", "raster-color-mix", mix);
  eid("dest-legend-b").updateMaxValue(maxValue);
}
scaleSliderB.onchange = function () {
  storagePersistElement(scaleSliderB);
}

// Since v3.0 on 2023-11-29, Mapbox GL JS has layers with raster-color.
// There was no obvious way to call the method that converts the raster-color spec to a color map for rendering.
// However, the prepared color ramp was visible on the raster layers using map.getLayer('travel-time').colorRamp.
// As of v3.6 this field is no longer visible in JS scripts or via the debugger.
// Searching for colorRamp shows the field is present in src/style/style_layer/raster_style_layer.js in both the v3.0
// and v3.6 source code. Logging map.getLayer in the console shows some t.implementation and t.serialize logic that may
// filtering some internal fields out. If we replicate the first step of the getLayer method and call
// map.style.getOwnLayer('travel-time') the field is once again visible.

const timeColorRampSelect = eid("time-color-ramp-select");
timeColorRampSelect.onchange = function () {
  const ramp = timeColorRamps[this.value];
  map.setPaintProperty("travel-time", "raster-color", ramp);
  // This is copying a 256 * 4 byte color map (an RGBAImage). See implementation notes above.
  accessDisplay.updateColorRamp(map.style.getOwnLayer('travel-time').colorRamp);
  storagePersistElement(timeColorRampSelect);
};

// TODO replace with generic raster-control elements
const colorSelectA = eid("color-select-a");
colorSelectA.onchange = function () {
  const ramp = destColorRamps[this.value];
  map.setPaintProperty("dest-a", "raster-color", ramp);
  // This is copying a 256 * 4 byte color map (an RGBAImage). See implementation notes above.
  eid('dest-legend-a').updateColorRamp(map.style.getOwnLayer('dest-a').colorRamp);
  storagePersistElement(colorSelectA);
}

const colorSelectB = eid("color-select-b");
colorSelectB.onchange = function () {
  const ramp = destColorRamps[this.value];
  map.setPaintProperty("dest-b", "raster-color", ramp);
  // This is copying a 256 * 4 byte color map (an RGBAImage). See implementation notes above.
  eid('dest-legend-b').updateColorRamp(map.style.getOwnLayer('dest-b').colorRamp);
  storagePersistElement(colorSelectB);
}

const networkSelect = eid("network-select");
const egressSelect = eid("egress-select");
const transitModeSelect = eid('transit-mode-select');
const streetModeSelect = eid('street-mode-select');
const dateSelect = eid("date-select");
const timeSelect = eid("time-select");
const durationSelect = eid("duration-select");
const windowSelect = eid("window-select");


networkSelect.newValueCallback = function (networkId, metadata) {
  storagePersistElement(networkSelect.select, 'network');
  if (!metadata) {
    // No valid network (or None) selected. Remove visualizations.
    setGtfsVisible(false);
    setOsmVisible(false);
    return;
  }
  const wb = metadata.wgsBounds;
  if (wb) {
    const lon = wb.minLon + wb.widthLon / 2;
    const lat = wb.minLat + wb.heightLat / 2;
    // easeTo moves slowly instead of jumping, but only if "reduce motion" is not enabled or overridden.
    // https://docs.mapbox.com/mapbox-gl-js/api/map/#map#easeto
    // map.easeTo({center: [lon, lat], duration: 2000, essential: true});
    map.setCenter([lon, lat]);
  }
  // Set street and transit vector tile sources to match the network ID.
  // Methods to set OSM and GTFS IDs also make those map layers visible.
  mapSetOsmId(networkId);
  mapSetGtfsId(networkId);
}

// While dragging, do not kick off new one-to-many searches until the previous one has finished.
// 60 frames per second requires a 16ms response time, which we do get for walk-only searches.
// Throttle to 30 frames per second to avoid pointlessly rendering multiple frames per monitor refresh.
let waitingForCalculation = false;
let lastFrameFinished = 0;
const MIN_FRAME_DURATION_MS = 1000/30;

/**
 * ONE-TO-MANY SEARCH
 * Make a request to the backend for travel time, access, and opportunity density information for a given origin point.
 * This is usually triggered on clicking the map or dragging a marker, but for now is also used to start a regional
 * analysis (using the launchBatch parameter).
 * @param point {lngLat} The origin point for the one-to-many request.
 * @param launchBatch {boolean} If true, also start a many-to-many batch. Defaults to false.
 */
const oneToMany = (point, launchBatch = false) => {
  waitingForCalculation = true;
  // Currently the endpoint URL must have a trailing slash to avoid a spurious redirect.
  // URL objects are always absolute, so a relative location requires the second parameter to make it absolute.
  const url = new URL('/otm/', document.location);
  // TODO use some method other than query parameters to send the request, while considering cacheing.
  // However we need to hit the URL first to get headers / JSON embedded as tags before the map hits it, and request
  // bodies and POST requests will not cooperate with caching.
  // May need to introduce a random requestId in the URL to ensure cache is hit.
  const searchParams = {
    lat: point.lat,
    lon: point.lng,
    // MDN: "The value of the time input is always in 24-hour format that includes leading zeros: hh:mm"... "displayed
    // date is formatted based on the locale of the user's browser, but the parsed value is always formatted yyyy-mm-dd."
    date: dateSelect.value,
    time: timeSelect.value,
    window: parseInt(windowSelect.value),
    duration: parseInt(durationSelect.value),
    network: networkSelect.getSelected(),
    egress: egressSelect.getSelected(),
    streetModes: streetModeSelect.getSelected(),
    transitModes: transitModeSelect.getSelected(),
    destinationId: destSelectA.getSelected(),
    // decayFunction: decaySelect.getSelected(),
    launchBatch: launchBatch
  };
  if (launchBatch) {
    searchParams.destinationId = eid("batch-dest-select").getSelected();
    searchParams.originId = eid('batch-origin-select').getSelected();
    searchParams.originFilter = eid('batch-origin-filter').value;
  }
  url.search = new URLSearchParams(searchParams);
  // The fetch result will resolve as soon as it has the headers. The arrayBuffer result will
  // resolve only when the body finishes arriving. Await decoding of this response body before
  // updating the map layers, as this ensures the body is already in the browser cache before the
  // map tries to fetch it again.
  fetch(url).then(async response => {
    let bbox = response.headers.get("x-geopng-bbox");
    await response.arrayBuffer()
      .then(buf => decodePngChunks(buf))
      .then(pngMeta => {
        console.log(pngMeta);
        accessDisplay.updateAccess(pngMeta['access']);
        accessDisplay.updateDensity(pngMeta['density']);
        accessDisplay.updateDual(pngMeta['dual']);
      });
    if (launchBatch) {
      return; // Avoid starting two batches in case of a cache miss.
    }
    if (bbox) {
      // TODO de-duplicate code
      bbox = bbox.split(",").map(parseFloat);
      const [minLon, minLat, maxLon, maxLat] = bbox;
      const bounds = {minLon, minLat, maxLon, maxLat};
      updateRasterLayer(map, 'travel-time', url.toString(), bounds);
      // setTimeSource(url, bbox); // Image fetch should hit cache from completed fetch.
    } else {
      console.log("No bounding box header present.")
    }
    lastFrameFinished = Date.now();
    waitingForCalculation = false;
  });
}

// The marker for the last selected one-to-many origin point, if any.
let originMarker = null;

// Clicking on the map will send a one-to-many travel time request and display the results on the map.
// See API reference at https://docs.mapbox.com/mapbox-gl-js/api/sources/
map.on('click', e => {
  const point = e.lngLat;
  if (!originMarker) {
    originMarker = new mapboxgl.Marker({color: '#4c6b7a', draggable: true}).setLngLat(point).addTo(map);
    originMarker.on("dragend", e => {
      const point = originMarker.getLngLat();
      oneToMany(point);
    });
    originMarker.on('drag', e => {
      if (!waitingForCalculation && (Date.now() - lastFrameFinished > MIN_FRAME_DURATION_MS)) {
          const point = originMarker.getLngLat();
          oneToMany(point);
      }
    });
  } else {
    originMarker.setLngLat(point);
  }
  oneToMany(point);
})

// TODO review onclick vs on('click') vs. addEventListener('click') differences
eid('launch-batch-button').onclick = (e) => {
  e.target.disabled = true;
  const point = originMarker.getLngLat();
  oneToMany(point, true);
  e.target.disabled = false;
}

/** Restore the last selected values for most controls from local storage. */
const restoreAllElements = () => {
    // FIXME Note that it's mainly the networkSelect elements that require complicated syntax
    storageRestoreElement(networkSelect.select, 'None', 'network');
    storageRestoreElement(timeColorRampSelect);
    storageRestoreElement(ttSlider, 30, 'travel-time');
    storageRestoreElement(percentileSlider, 1, 'percentile');
    storageRestoreElement(destSelectA.select, 'None', 'dest-a');
    storageRestoreElement(colorSelectA);
    storageRestoreElement(scaleSliderA);
    storageRestoreElement(destSelectB.select, 'None', 'dest-b');
    storageRestoreElement(colorSelectB);
    storageRestoreElement(scaleSliderB);
}

// eid("search-button").addEventListener('click', e => {
//   oneToMany(originMarker.getLngLat());
// });
