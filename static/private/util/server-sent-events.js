// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

// This module connects to the backend server-sent events endpoint, providing a common interface for all components
// that wish to display or react to incoming messages.
// See https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events

export let tabId = null;

// TODO Possibly maintain tab ID when navigating to a new page within the same tab.
// Currently every navigation causes a disconnect and reconnect with a new ID.

const eventSource = new EventSource("/events/");

eventSource.onmessage = (event) => {
  console.log("Server-sent message:", event.data);
}

eventSource.onerror = (error) => {
  console.log("Server-sent event error:", error);
  eventSource.close();
}

eventSource.addEventListener('connect', event => {
  if (tabId !== null) {
    console.log("Overwriting existing tab ID. Did EventSource send more than one connect event?");
  }
  tabId = event.data;
  console.log("Connected to EventSource. Server assigned us tab ID: " + tabId);
});

export function addSseListener (eventType, listener) {
  eventSource.addEventListener(eventType, event => listener(event.data))
}
