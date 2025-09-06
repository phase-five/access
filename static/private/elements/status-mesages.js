// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

import {addSseListener} from "../util/server-sent-events.js";
import {ce} from '../util/util.js'

/**
 * HTML custom element to inform the user of actions or problems with basic text strings.
 * The messages must be added manually by calling addMessage() and can only be cleared by clicking the button.
 * This could be improved to coordinate with progress bars, clear some messages automatically, scroll etc.
 */
class StatusMesages extends HTMLElement {

  // An HTML paragraph element holding the messages.
  // Eventually this should probably be modeled as nested custom elements to facilitate styling and layout.
  messageArea;

  connectedCallback() {
    const title = ce("h3");
    title.innerText = "Status Messages";
    // Textarea is obnoxious to style. It always wants to shrink to a very small size.
    // const messageArea = document.createElement("textarea");
    // messageArea.contentEditable = 'false';
    const messageArea = ce("div");
    messageArea.style.overflowY = "scroll";
    this.messageArea = messageArea;
    const button = ce("button");
    button.innerText = "Clear Messages";
    button.onclick = () => {
      messageArea.replaceChildren();
    };
    const ephemeralMessage = ce("div");
    const systemMessage = ce("div");
    // title, ... button
    this.append(ephemeralMessage, systemMessage, messageArea);
    addSseListener('status', data => {
      ephemeralMessage.innerText = data;
    });
    addSseListener('system', data => {
      systemMessage.innerText = data;
    });
  }

  addMessageWithHttpStatus (message, status) {
    const firstDigit = status.toString().charAt(0);
    const isError = firstDigit === '4' || firstDigit === '5';
    this.addMessage(message, isError);
  }

  addMessage (message) {
    this.addMessage(message, false);
  }

  addMessage (message, isError) {
    const msgDiv = ce("div");
    msgDiv.style.width = "98%";
    msgDiv.innerText = message;
    if (isError) {
      // bgColor attribute is deprecated in favor of CSS
      msgDiv.style.backgroundColor = "#fcc";
    }
    this.messageArea.append(msgDiv);
  }

}
customElements.define("status-messages", StatusMesages);
