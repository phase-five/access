// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

/**
 * This module provides a single shared way to create a mapbox map with some standardized layers and configuration.
 * It expects a single <div id="map"> to be present in the current HTML document.
 * For example, both a map layer controls custom element and the page's main JS code can import this same module.
 *
 * Another possibility is passing a map wrapper object in to each of these components or contexts where it is used.
 * This dependency injection approach may be overkill for a web app where we can simply use classless modules.
 * Eventually, the map div and associated reusable code could be an HTML custom element. But as long as there's only
 * ever one map per page, it's syntactically simpler to just make a module (less "this" nonsense).
 *
 * Imports are nested as follows, for example: modify.html -> modify.js -> map-common.js -> mapbox-gl.js
 * Thus the only thing visible outside this module (in modify.js and above) is the map instance and whatever helper
 * methods are imported by modify.js.
 *
 * NOTE any HTML page that makes use of this module must link to the Mapbox stylesheet corresponding to the version of
 * Mapbox GL imported below. Several attempts to attach the stylesheet tag automatically led to out-of-order loading.
 *
 * @module MapCommon
 */

const MAPBOX_ACCESS_TOKEN = 'YOUR_MAPBOX_KEY_HERE';

// Importing this script from CDN will create the mapboxgl global variable.
// The imported file is not really a proper ES6+ module, this is a side-effect only import.
import 'https://api.mapbox.com/mapbox-gl-js/v3.13.0/mapbox-gl.js';

mapboxgl.accessToken = MAPBOX_ACCESS_TOKEN;

// TODO Do we need to defer initialization to be sure the on-load callback is registered early enough?
//      e.g. export let map = null;
export const map = new mapboxgl.Map({
  container: 'map',
  style: 'mapbox://styles/abyrd/clktgoyey006q01qva4tqbgod',
  projection: 'equirectangular', // To match projection of our very large PNG rasters.
  pitch: 0,
  bearing: 0,
  zoom: 9
});

function  addOsmSourceAndLayer () {
  map.addSource('osm-src', {
    'type': 'vector',
    'tiles': [ /*EMPTY*/ ], // Causes an error if null.
    'minzoom': 11,
    'maxzoom': 22
  });
  map.addLayer({
    'id': 'osm-edges',
    'type': 'line',
    'source': 'osm-src',
    'source-layer': 'osm-edges',
    'paint': {
      // Street classes appear at zooms 8, 10, 11, 12, 13
      // Maybe these should all be separate layers in the vector tiles.
      'line-opacity': 0.5, // ['interpolate', ['linear'], ['zoom'], 0, 0, 11, 0, 20, 0.5],
      'line-color': 'rgb(50, 50, 50)',
      'line-width': 0.5
    }
  }, 'road-simple');
  // Hide all OSM tile layers until a source is fully specified. This should prevent tile loads.
  setOsmVisible(false);
}

/**
 * Add map layers for GTFS Patterns and Stops.
 * Mapbox layer style reference is at https://docs.mapbox.com/style-spec/reference/layers
 */
function addGtfsSourceAndLayer () {
  map.addSource('gtfs-src', {
    'type': 'vector',
    'tiles': [ '' ], // Causes an error if null, empty list, or bad URL.
    'minzoom': 10,
    'maxzoom': 15
  });
  map.addLayer({
    'id': 'gtfs-patterns',
    'type': 'line',
    'source': 'gtfs-src',
    'source-layer': 'gtfs-patterns',
    'paint': {
      'line-opacity': {
        'base': 0,
        'stops': [
          [12, 0.4],
          [15, 0.5]
        ]
      },
      'line-color': 'rgb(50, 50, 50)',
      'line-width': 2
    },
    'minzoom': 10,
    'maxzoom': 22
  }, 'road-simple');
  map.addLayer({
    'id': 'gtfs-stops',
    'type': 'circle',
    'source': 'gtfs-src',
    'source-layer': 'gtfs-stops',
    'paint': {
      'circle-radius': {
        'base': 1,
        'stops': [
          [12, 1],
          [16, 4]
        ]
      }
    },
    'minzoom': 12,
    'maxzoom': 22
  }, 'road-simple');
  map.addLayer({
    'id': 'gtfs-stop-label',
    'type': 'symbol',
    'source': 'gtfs-src',
    'source-layer': 'gtfs-stops',
    // no layout.iconImage, text only.
    // Stop properties are set in GTFSCache.buildStopsIndex
    'layout': {
      'text-field': ['get', 'name'],
      'text-anchor': 'top-left',
      'text-offset': [0.5, 0.5],
      'text-size': 12
    },
    'paint': {
      'text-color': 'rgb(0, 0, 0)',
      'text-halo-width': 1,
      'text-halo-color': '#fff'
    },
    'minzoom': 15,
    'maxzoom': 22
  }, 'road-label-simple');
  // Hide all GTFS layers until a source is fully specified. This should prevent tile loads.
  setGtfsVisible(false);
}

/**
 * @callback CustomMapConfigCallback
 * @param map the map currently being initialized, on which addSource and addLayer are typically called.
 */

/**
 * Register the standard onLoad handler, which adds the common layers used throughout all maps in the app, then runs
 * the supplied callback as the last step in the onload handler (to add any custom layers for a specific page).
 * @param customSteps {CustomMapConfigCallback}
 * @return {mapboxgl.Map} the map object so the caller can hold on to it
 */
export const mapConfigure = (customSteps) => {
  // Map object is already created when const is initialized, but maybe create it here?
  // We take a risk that the on-load event has already fired (due to some other import of this same module) by the
  // time we register it here. Another possibility: if (map.loaded) x() else map.on('load', x);
  // Edit base style at: https://studio.mapbox.com/styles/abyrd/clktgoyey006q01qva4tqbgod/
  // Hillshade from raster DEM seems to be blurred in this style.

  // map = new mapboxgl.Map({
  //   container: 'map',
  //   style: 'mapbox://styles/abyrd/clktgoyey006q01qva4tqbgod'
  //   // center: [-122.8155055, 45.4965002],
  //   // zoom: 9,
  // });
  // Register the load handler in this same function, before event loop has a chance to do anything at all with the Map.
  map.on("load", () => {
    addOsmSourceAndLayer();
    addGtfsSourceAndLayer();
    map.addControl(new mapboxgl.ScaleControl({maxWidth: 200, unit: 'metric'}));
    // map.addControl(new mapboxgl.FullscreenControl());
    customSteps(map);
  });
  return map;
}

/**
 * FIXME this is actually the StreetLayer, not exactly OSM.
 * @param networkId {string}
 */
export function mapSetOsmId (networkId) {
  const urlTemplate = `${window.location.origin}/mvt/osm/${networkId}/{z}/{x}/{y}.mvt`;
  const source = map.getSource('osm-src');
  if (!source) throw new Error("OSM layer must already exist on map.");
  source.setTiles([urlTemplate]);
}

/**
 * @param layerId {string}
 * @param visible {boolean}
 */
export function setLayerVisible (layerId, visible = true) {
  map.setLayoutProperty(layerId, 'visibility', visible ? "visible" : "none");
}

/**
 * @param visible {boolean}
 */
export function setGtfsVisible (visible = true) {
  setLayerVisible('gtfs-patterns', visible);
  setLayerVisible('gtfs-stops', visible);
  setLayerVisible('gtfs-stop-label', visible);
}

/**
 * Sets the visibility of network street edge vector tiles.
 * TODO fully separate methods that control OSM base map layers from our vector layers for network edges. Rename them.
 * @param visible {boolean}
 */
export function setOsmVisible (visible = true) {
  setLayerVisible('osm-edges', visible);
}

/**
 * Set the visibility for all the various layers for roads, bridges, tunnels, paths, steps, etc. to the same value.
 * @param visible {boolean} if true the layers will be made visible, if false the layers will be made invisible.
 */
export function setAllStreetLayersVisible (visible) {
  // Specifying whole categories of layers like 'surface' and 'barriers-bridges' don't work.
  // It would be possible to factor out bridge-road-tunnel from the layer names in a loop, but we don't want to
  // introduce any extra computation or string-splicing here.
  setLayerVisible('land-structure-polygon', visible);
  setLayerVisible('bridge-simple', visible);
  setLayerVisible('bridge-case-simple', visible);
  setLayerVisible('bridge-pedestrian', visible);
  setLayerVisible('bridge-steps', visible);
  setLayerVisible('bridge-path', visible);
  setLayerVisible('bridge-path-cycleway-piste', visible);
  setLayerVisible('bridge-path-trail', visible);
  setLayerVisible('road-simple', visible);
  setLayerVisible('road-pedestrian', visible);
  setLayerVisible('road-steps', visible);
  setLayerVisible('road-path', visible);
  setLayerVisible('road-path-cycleway-piste', visible);
  setLayerVisible('road-path-trail', visible);
  setLayerVisible('tunnel-simple', visible);
  setLayerVisible('tunnel-pedestrian', visible);
  setLayerVisible('tunnel-steps', visible);
  setLayerVisible('tunnel-path', visible);
  setLayerVisible('tunnel-path-cycleway-piste', visible);
  setLayerVisible('tunnel-path-trail', visible);
}

/**
 * A big question here is whether we can have layers and sources defined but with no associated data or tile URLs.
 * We start out with no data on the map - do we need to add the source and layer only when the data become available?
 * Or can we add them empty and update later to avoid having so many null checks?
 * Layers cause errors when the URL is an empty array, but what about null?
 * However, it requires a similar amount of lines to set visibility (though less than for creating the layers).
 *
 * "The expected behavior is that we'll stop requesting tiles when there are no layers within a style that are
 * currently using a given source."
 */
export function mapSetGtfsId (gtfsId) {
  // Relative URLs are not supported. Construct absolute URL using address of current page.
  // Other ideas here: https://github.com/mapbox/mapbox-gl-js/issues/3636#issuecomment-578090944
  const urlTemplate = `${window.location.origin}/mvt/gtfs/${gtfsId}/{z}/{x}/{y}.mvt`
  const source = map.getSource('gtfs-src');
  if (!source) throw new Error("GTFS source must already exist on map.");
  source.setTiles([urlTemplate]);
  // Do not automatically enable vector tile display
  // setGtfsVisible(true);
}

// Units are probably (non-retina) pixels.
export const CLICK_RADIUS = 2;
export const DOUBLE_CLICK_RADIUS = 4;
export const DRAG_RADIUS = 4;

/**
 * Make the minimum bounding box containing a circle at the given point with radius r.
 * Mapbox mouse events have both lngLat (in degrees) and point (in pixels). This works on pixels.
 * @param point {{x: number, y: number}}
 * @param r {number}
 */
export function queryBox(point, r) {
  return [[point.x - r, point.y - r], [point.x + r, point.y + r]];
}
