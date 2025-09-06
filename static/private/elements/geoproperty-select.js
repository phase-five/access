// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import { ce, option } from "../util/util.js";

/**
 * HTML custom element allowing selection of a layer and a property within that layer, within a GeoPackage.
 * TODO If there is only one source or layer, it should automatically be selected.
 */
class GeoPropertySelect extends HTMLElement {

  fileMetadata;
  layerSelect;
  propertySelect;

  constructor() {
    super();
  }

  /**
   * @param fileMetadata {{layers:[{name: {string}, layerType: {string}, crs: {string}}]}}
   */
  setMetadata = (fileMetadata) => {
    this.fileMetadata = fileMetadata;
    // Update the layer selection dropdown based on the newly supplied metadata.
    this.layerSelect.replaceChildren(option("Select a layer", null));
    if (fileMetadata) {
      for (const layer of fileMetadata.layers) {
        this.layerSelect.append(option(`${layer.name} (${layer.layerType} in ${layer.crs})`, layer.name));
      }
    }
    this.propertySelect.replaceChildren();
  }

  connectedCallback() {
    const layerSelect = ce("select");
    const propertySelect = ce("select");
    layerSelect.onchange = () => {
      const layer = this.fileMetadata.layers.find(l => l.name === layerSelect.value);
      this.propertySelect.replaceChildren(option("Select a property of the layer", null));
      for (const property of layer.properties) {
        this.propertySelect.append(option(`${property.name} (${property.propertyType})`, property.name));
      }
    }
    this.append(layerSelect, propertySelect);
    this.layerSelect = layerSelect;
    this.propertySelect = propertySelect;
  }

  /**
   * Return the currently selected layer and property.
   * @returns {{property: {string}, layer: {string}}}
   */
  getSelected() {
    return {
      layer: this.layerSelect.value,
      property: this.propertySelect.value
    };
  }

}

customElements.define("geoproperty-select", GeoPropertySelect);
