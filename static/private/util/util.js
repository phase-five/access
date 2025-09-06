// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

/**
 * This module contains utility functions for reuse across other modules and custom elements.
 * Use with: `import {eid, qs} from './components/util.js'`
 * Note that relative import paths must be clearly relative using a dot, and must include the filename extension.
 * @module Util
 */

export const EMPTY_GEOJSON = {'type': 'FeatureCollection', 'features': []};

/**
 * Shorthand to create an HTML element of a given kind. createElement is supposedly faster than parsing innerHtml.
 * @returns {HTMLElement}
 */
export function ce (e) {
  return document.createElement(e);
}

/**
 * Shorthand to fetch an HTML element by its ID.
 * @returns {HTMLElement}
 */
export function eid (id) {
  return document.getElementById(id);
}

/**
 * Shorthand to fetch an HTML element using a query selector (such as a custom element name).
 * @returns {HTMLElement}
 */
export function qs (q) {
  return document.querySelector(q);
}

/**
 * Create an option element with the given innerText and value.
 * @param text {string}
 * @param value {string}
 */
export function option (text, value) {
  const option = ce("option");
  option.innerText = text;
  option.value = value;
  return option;
}

/**
 * @param isHeader {boolean}
 * @param items {[(Node|string)]}
 * @returns {HTMLTableRowElement}
 */
function makeTableRowInternal (isHeader, items) {
  const tr = ce("tr");
  for (const item of items) {
    const cell = ce(isHeader ? "th" : "td");
    cell.append(item);
    tr.appendChild(cell);
  }
  return tr;
}

/**
 * Usage:
 * ```
 * const table = ce("table");
 * table.appendChild(makeTableHeaderRow("x", "y"));
 * table.appendChild(makeTableDataRow("1", "2"));
 * ```
 * @param items {(Node|string)}
 * @returns {HTMLTableRowElement}
 */
export function makeTableDataRow (...items) {
  return makeTableRowInternal(false, items);
}

/**
 * @see makeTableHeaderRow
 * @param items {string}
 * @returns {HTMLTableRowElement}
 */
export function makeTableHeaderRow (...items) {
  return makeTableRowInternal(true, items);
}

/**
 * @param name the name of an SVG element to create
 * @returns {SVGElement}
 */
export function csvg (name) {
  return document.createElementNS('http://www.w3.org/2000/svg', name);
}

export function titleCase (s) {
  return s.charAt(0).toUpperCase() + s.substring(1).toLowerCase();
}

/**
 * Shorthand to save a key-value pair to browser local storage.
 * @param key {string}
 * @param val {string}
 */
export function storageSet (key, val) {
  window.localStorage.setItem(key, val);
}

/**
 Shorthand to retrieve that value for a given key from browser local storage.
 * @param key {string}
 * @return {string}
 */
export function storageGet (key) {
  return window.localStorage.getItem(key);
}

/**
 * @param element {HTMLElement} The element with a value property to be restored.
 * @param defaultVal {string} The value to restore if the key is not present in the localStorage.
 * @param key {string} A unique key for the localStorage. If not provided it will fall back on the element id.
 */
export function storageRestoreElement (element, defaultValue = 'None', key) {
  // Auto key-detection does not work in custom elements like file-select that wrap another input element with no ID.
  // You also can't use element.parentElement because sometimes it's nested a few layers deep.
  // Either directly subclassing the SelectElement or giving your custom element a value attribute could solve this.
  if (!key) key = element.id;
  if (!key) throw new Error('When restoring from localStorage, no key provided and element has no ID.');
  const storedValue = storageGet(key);
  element.value = storedValue || defaultValue;
  fireChanged(element);
}

/**
 * @param element {HTMLElement} The element with a value property to be persisted.
 * @param key {string} A unique key for the localStorage. If not provided it will fall back on the id and tag name.
 */
export function storagePersistElement (element, key) {
  if (!key) key = element.id;
  if (!key) throw new Error('When setting localStorage, no key provided and element has no ID.');
  storageSet(key, element.value);
}

/**
 * Trigger any callbacks on the specified element as if its value had been changed. The usual advice is to not simulate
 * an event this way, and to instead factor out the change methods and call them when doc is ready. One good reason is
 * that the event may be deferred, and you need to sequentially carry out a series of actions to restore the page.
 * @param element {HTMLElement}
 */
export function fireChanged (element) {
  element.dispatchEvent(new Event('input'));
  element.dispatchEvent(new Event('change'));
}

/**
 * @param n {number}
 * @return {string}
 */
export function human (n) {
  if (n >= 1000000) {
    return (n/1000000).toFixed(1).toString() + 'M';
  } else if (n > 10000) {
    return (n/1000).toFixed(0).toString() + 'k';
  } else if (n > 5000) {
    return (n/1000).toFixed(1).toString() + 'k';
  } else {
    return n.toFixed().toString();
  }
}