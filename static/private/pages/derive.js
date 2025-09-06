// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// HTML Custom elements used by this page.
// Nest imports here instead of using noisy script tags in parent HTML file.
import "../elements/nav-bar.js";
import "../elements/file-list.js";
import "../elements/file-select.js";
import "../elements/geoproperty-select.js";
import "../elements/progress-bars.js";
import "../elements/status-mesages.js";

import {eid, qs} from '../util/util.js'

const statusMessages = qs("status-messages");

/**
 * HTTP POST the supplied object as JSON to the given URL, and display any text response.
 * Ultra confusingly, POST to URL paths without a trailing slash are silently redirected to GET
 * for no apparent reason. https://stackoverflow.com/a/39056469/778449
 * @param body {Object}
 * @param url {String}
 * @return {Promise<void>}
 */
const doPost = async (body, url) => {
  const fetchParameters = {
    method: "POST",
    body: JSON.stringify(body), // Without explicit stringify, body is sent as `[object Object]`
    headers: {"Content-Type": "application/json"}
  };
  const response = await fetch(url, fetchParameters);
  const txt = await response.text();
  statusMessages.addMessage(txt);
}

// Network Build
{
  const name = eid("build-name-text");
  const detail = eid("build-detail-text");
  const gtfsSelect = eid("build-gtfs-select");
  const osmSelect = eid("build-osm-select");
  eid("build-button").onclick = async () => {
    // The GTFS is a multi-select checkbox list, while the OSM dropdown only allows selecting one OSM.
    // TODO validate presence of at least one of each, and name and description.
    const gtfsFiles = gtfsSelect.getSelected();
    const osmFile = osmSelect.getSelected();
    const body = {
      files: [...gtfsFiles, osmFile],
      name: name.value,
      description: detail.value
    };
    doPost(body, "/netbuild/");
  };
}

// GeoPackage Import
{
  const network = eid("gpkg-network-select");
  const geopackage = eid("gpkg-file-select");
  const property = eid("gpkg-prop-select");
  const points = eid("gpkg-points-select");
  const name = eid("gpkg-name-text");
  const detail = eid("gpkg-detail-text");
  geopackage.newValueCallback = (id, metadata) => {
    property.setMetadata(metadata);
  };
  eid("gpkg-import-button").onclick = async () => {
    const selectedProperty = property.getSelected();
    const body = {
      name: name.value,
      description: detail.value,
      source: geopackage.getSelected(),
      layer: selectedProperty.layer,
      property: selectedProperty.property,
      network: network.getSelected(),
      points: points.value
    };
    doPost(body, "/rasterize/");
  };
}

// Precompute Egress Tiles
{
  const network = eid("egress-network-select");
  const name = eid("egress-name-text");
  const detail = eid("egress-detail-text");
  const mode = eid("egress-mode-select");
  const distance = eid("egress-distance-select");
  eid("egress-button").onclick = async () => {
    const networkId = network.getSelected();
    // Other parameters are not currently sent, hard-wired to 1km walk.
    // This should also be migrated to use doPost with a JSON body for consistency.
    await fetch("/egress/?network=" + networkId);
  }
}

// File Listing
{
  // Once any background tasks are completed, display any newly created files.
  // TODO MAKE SURE FILE LIBRARY REFRESHES WHEN POINT IS BUILT (NOT JUST GRID)
  qs("progress-bars").onAnyCompleted = async () => {
    eid("file-list").refetch();
    eid("gpkg-network-select").refetch();
    eid("egress-network-select").refetch();
  }
}
