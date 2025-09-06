// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// Color maps for visualizing opportunity data and travel times.
// For use with Mapbox GL JS raster-color on raster layers.

const greenStep = ["step", ["raster-value"],
  "rgb(220, 250, 220)",
  0.50, "rgb(180, 190, 180)"
];

const alphaStep = ["step", ["raster-value"],
  "rgba(0, 0, 0, 0.0)",
  0.50, "rgba(0, 0, 0, 0.4)"
];

const colorRamp = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.125, "rgb(100, 200, 100)",
  0.25, "rgb(100, 100, 200)",
  0.375, "rgb(150, 100, 100)",
  0.50, "rgb(255, 255, 255)"
];

const densityAlphaRed = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 0, 0)",
  1.0, "rgba(255, 0, 0, 0.5)"
];

const densityAlphaBlueRed = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 0, 0)",
  0.5, "rgba(0,30,255,0.5)",
  1.0, "rgba(255, 0, 0, 0.5)"
];

const densityAlphaBlue = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 0, 0)",
  1.0, "rgba(0, 0, 255, 0.5)"
];

const densityAlphaGreenBlue = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 0, 0)",
  0.5, "rgba(0, 180, 0, 0.5)",
  1.0, "rgba(0, 0, 255, 0.5)"
];

const densityAlphaGreen = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 0, 0)",
  1.0, "rgba(0, 180, 0, 0.5)"
];

const densityRedGreenBlue = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(255, 0, 0, 0)",
  0.25, "rgb(255, 0, 0)",
  0.50, "rgb(0, 255, 0)",
  0.75, "rgb(0, 0, 255)",
  1.00, "rgb(255, 0, 255)"
];

const densitySpectrum = ["interpolate",
  ["linear"],
  ["raster-value"],
  0.0, "rgba(0, 0, 255, 0)",
  0.25, "rgb(0, 0, 255)",
  0.50, "rgb(0, 200, 0)",
  0.75, "rgb(240, 240, 0)",
  1.00, "rgb(240, 0, 0)",
];

const bands = [ "step",
  ["raster-value"],
  "rgb(50, 150, 50)",
  0.1, "rgb(178, 205, 174)",
  0.2, "rgb(221, 207, 153)",
  0.3, "rgb(207, 155, 103)",
  0.4, "rgb(227, 210, 197)",
  0.5, "rgb(255, 255, 255)"
];

const alphaBands3 = [ "step",
  ["raster-value"],
  'rgba(0, 0, 0, 0.0)',
  0.1666, 'rgba(0, 0, 0, 0.3)',
  0.3333, 'rgba(0, 0, 0, 0.6)',
  0.5000, 'rgba(0, 0, 0, 0.8)'
];

const alphaBands4 = [ "step",
  ["raster-value"],
  'rgba(0, 0, 0, 0.0)',
  0.125, 'rgba(0, 0, 0, 0.2)',
  0.250, 'rgba(0, 0, 0, 0.4)',
  0.375, 'rgba(0, 0, 0, 0.6)',
  0.500, 'rgba(0, 0, 0, 0.8)'
];

const transparent = ["step", ["raster-value"],
  "rgba(0, 0, 0, 0.0)",
  1.0, "rgba(0, 0, 0, 0.0)"
];

// Look up color maps by the string values used in HTML select elements.

export const timeColorRamps = {
  'transparent':   alphaStep,
  'alpha-bands-3': alphaBands3,
  'alpha-bands-4': alphaBands4,
  'color-bands':   colorRamp,
  'green':  greenStep,
  'bands':  bands
};

// TODO rename, now used for both destinations and results ("density")
export const destColorRamps = {
  'red': densityAlphaRed,
  'red-blue': densityAlphaBlueRed,
  'blue': densityAlphaBlue,
  'blue-green': densityAlphaGreenBlue,
  'green': densityAlphaGreen,
  'rgb': densityRedGreenBlue,
  'spectrum': densitySpectrum,
  'none': transparent
};
