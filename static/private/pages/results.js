// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// For browsing and downloading the results of regional analyses.

// HTML Custom elements used by this page. Nest imports here instead of using noisy script tags in parent HTML file.
import "../elements/nav-bar.js";
import "../elements/file-select.js";
import "../elements/raster-control.js";
import "../elements/map-layers.js";
import "../elements/progress-bars.js";
import "../elements/status-mesages.js";

// Utility methods and modules used in analysis.
import {eid, EMPTY_GEOJSON,} from "../util/util.js";
import {mapConfigure, mapSetGtfsId, mapSetOsmId} from "../util/map-common.js";
import {addSseListener} from "../util/server-sent-events.js";

const map = mapConfigure((map) => {
  // TODO This is based on the addRasterLayer function in analyze.js. Factor it out into map-common.
  map.addSource('results', {
    'type': 'image',
    'url': "../png/blank.png",
    'coordinates': [[0, 1], [1, 1], [1, 0], [0, 0]]
  });
  // TODO factor out method to add a raster layer that is preconfigured to work with the raster-control.js element.
  // The indicator value that will be mapped to 1.0 on the color ramp.
  const maxValue = 400000;
  map.addLayer({
    'id': 'results',
    'type': 'raster',
    'source': 'results',
    'paint': {
      // At each pixel, the value of each channel in [0, 255] is normalized to the range [0, 1] before being multiplied
      // by the mix parameters which are specified in the order [R, G, B, A]. Thus, the mix parameters are the the
      // maximum value that each channel byte will represent when its value is 255. We scale these multipliers to
      // effectively re-normalize the output range [0, maxValue] to [0, 1] so we can reuse the same color maps covering
      // range [0, 1] for any user-specified maxValue.
      // See https://github.com/mapbox/mapbox-gl-js/pull/12368 for a much better explanation than the Mapbox docs.
      "raster-color-mix": [(256*256*256-1)/maxValue, (256*256-1)/maxValue, (256-1)/maxValue, 0],
      "raster-color": ["interpolate",
        ["linear"],
        ["raster-value"],
        0.0, "rgba(0, 0, 0, 0)",
        0.5, "rgba(0, 50, 255, 0.8)",
        1.0, "rgba(255, 0, 0, 0.8)"
      ],
      "raster-color-range": [0, 1],
      "raster-resampling": 'nearest',
      "raster-fade-duration": 0,
    },
    'layout': { 'visibility': 'visible' }  // none
  }, "waterway");
  // Add layers to display the geographic location of active batch calculations.
  // Unlike some other layers these are initialized to be visible, but have an empty source.
  // We do not provide the layer order parameter, so each new layer will be the topmost.
  map.addSource('batch-finished', {'type': 'geojson', 'data': EMPTY_GEOJSON});
  map.addSource('batch-sent', {'type': 'geojson', 'data': EMPTY_GEOJSON});
  map.addLayer({id: 'batch-finished', source: 'batch-finished', type: 'fill',
    paint: {'fill-color': '#8585b1', 'fill-opacity': 0.7}});
  map.addLayer({id: 'batch-sent', source: 'batch-sent', type: 'line',
    paint: {'line-color': '#880000', 'line-width': 1}});
  // Initial map location. TODO set from results metadata
  map.setCenter([5.4, 43.3]);
});

// Ideally we'd query and display raster values on this page. But surprisingly, MapboxGL does not
// seem to have any way to evaluate a raster at a given point (on hover or click). The query methods
// all query "features", and listeners are triggered only when their position is within a feature
// on the specified layers. But in this model rasters don't have features. This issue seems to be
// tracking the problem (for a decade): https://github.com/mapbox/mapbox-gl-js/issues/1404
// The only practical solution may be an API call to a stateful server, which does have the
// advantage of fetching paths and other supplemental information.
// Anyway more advanced analysis is intended to be done in QGIS via exported rasters.
const batchSelect = eid("batch-select");
const updateResultsRaster = () => {
  const resultsId = batchSelect.getSelected();
  const metadata = batchSelect.getSelectedMetadata();
  // Select elements can only provide their values as strings not numbers,
  // but they'll be substituted into a URL string anyway.
  const percentile = eid('percentile-select').value;
  const cutoff = eid('time-select').value;
  const pngUrl = `/results/${resultsId}?p=${percentile}&c=${cutoff}`;
  // Convert JSON-serialized Java WGSBounds (stored in backend metadata) into MapboxGL bounding coordinates.
  const {minLon, minLat, widthLon, heightLat} = metadata.wgsBounds;
  const maxLon = minLon + widthLon;
  const maxLat = minLat + heightLat;
  const coordinates = [
    [minLon, maxLat],
    [maxLon, maxLat],
    [maxLon, minLat],
    [minLon, minLat]
  ];
  // This is very similar to analyze.updateRasterLayer (factor out into map-common)
  map.getSource('results').updateImage({
    url: pngUrl,
    "coordinates": coordinates
  });
  const networkId = metadata.sources.NETWORK[0];
  // Backend tile methods will accept either network or osm/gtfs IDs.
  mapSetGtfsId(networkId);
  mapSetOsmId(networkId);
};

batchSelect.newValueCallback = updateResultsRaster;
eid('percentile-select').onchange = updateResultsRaster;
eid('time-select').onchange = updateResultsRaster;
eid('download-button').onclick = (e) => {
  const resultsId = batchSelect.getSelected();
  // For the case of initiating a download and save, fetch does not seem to work. Weirdly, you have
  // to set the location of the window, and if the response has an attachment content-disposition
  // header the file should be saved without changing the URL bar or the displayed page. Download
  // progress is shown in the browser as expected.
  window.location.assign(`/download/${resultsId}.geotiffs.zip`);
};


{
  // The server sends JSON arrays with one serialized Wgs84Bounds object per cell.
  // Convert these to GeoJSON polygons and set the specified map data source to that GeoJSON.
  // Tolerates undefined cell arrays because the message will only have one or two of them.
  const batchCellsToLayer = (source, cells) => {
    if (!cells) return;
    const features = cells.map(a => {
      const {x0, y0, dx, dy} = a;
      const [x1, y1] = [x0 + dx, y0 + dy];
      // Note that coordinates are nested three arrays deep, because a polygon is an array of rings.
      // GeoJSON Spec 3.1.6: A linear ring is a closed LineString with four or more positions. The
      // first and last positions... MUST contain identical values... exterior rings are
      // counterclockwise, and holes are clockwise.
      const polygon = [[[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]];
      return {
        'type': 'Feature',
        'geometry': {
          'type': 'Polygon',
          'coordinates': polygon
        }
      };
    });
    const featureCollection = {
      'type': 'FeatureCollection',
      'features': features
    };
    map.getSource(source)?.setData(featureCollection);
  }
  // Register an SSE listener to display the geographic location of active batch calculations.
  // We may want a separate SSE endpoint so clients don't constantly receive when not visualizing.
  let accumulatedFinished = [];
  addSseListener("batch-finished", data => {
    // Finished cells are received continuously one at a time.
    const cell = JSON.parse(data);
    accumulatedFinished.push(cell);
    batchCellsToLayer("batch-finished", accumulatedFinished);
  });
  addSseListener("batch-sent", data => {
    // Sent cells are received in blocks (as a JSON array).
    const batch = JSON.parse(data);
    batchCellsToLayer("batch-sent", batch);
    accumulatedFinished = [];
  });
}
