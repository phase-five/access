// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {ce} from "../util/util.js";

import {setAllStreetLayersVisible, setLayerVisible, setOsmVisible} from "../util/map-common.js";

/**
 * HTML custom element that allows toggling the visibility of the optional map layers.
 */
class MapLayers extends HTMLElement {

  /** @type {HTMLDivElement} */
  div;

  /**
   * @param labelText {string}
   * @param layerId {string}
   */
  cbox (labelText, layerId, checked = false) {
    const label = document.createElement("label");
    const cbox = document.createElement("input");
    cbox.type = "checkbox";
    cbox.value = layerId;
    cbox.checked = checked;
    cbox.addEventListener('change', (e) => {
      const layerId = e.target.value;
      const visible = e.target.checked;
      setLayerVisible(layerId, visible);
    });
    label.append(cbox, labelText);
    this.div.appendChild(label);
  }

  connectedCallback() {
    const div = ce("div");
    div.style.display = 'flex';
    div.style.flexFlow = 'column wrap';
    // div.style.border = "1px solid black";
    div.style.padding = "2px";
    div.style.height = "3.3lh"; // Three line-heights
    div.style.width = "96%";
    this.div = div;
    // Checkboxes mostly for vector layers generated from network, which are not visible by default.
    // Buildings and road names are part of the base map, and enabled by default in the style.
    // We can't alter layer visibility here to match checkboxes because style isn't loaded yet.
    this.cbox("Buildings", "building-extrusion", true);
    this.cbox("Network Edges", "osm-edges");
    this.cbox("Street Names", "road-label-simple", true);
    this.cbox("GTFS Patterns", "gtfs-patterns");
    this.cbox("GTFS Stops", "gtfs-stops");
    this.cbox("GTFS Stop Labels", "gtfs-stop-label");
    this.appendChild(div);
    // Single checkbox for all MapboxGL base map road layers (except labels).
    const osmLabel = ce("label");
    const osmCb = ce("input");
    osmCb.type = "checkbox";
    osmCb.checked = true;
    osmCb.addEventListener('change', (e) => {
      setAllStreetLayersVisible(e.target.checked);
    });
    osmLabel.append(osmCb, "OSM Base Map Streets");
    osmLabel.style.padding = "2px";
    this.appendChild(osmLabel);
  }

}

customElements.define("map-layers", MapLayers);
