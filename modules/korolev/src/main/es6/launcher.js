import { Connection } from './connection.js';
import { Bridge, setProtocolDebugEnabled } from './bridge.js';
import { ConnectionLostWidget } from './utils.js';

function showKorolevIsNotReadyMessage() {
  console.log("Korolev is not ready");
}

window['Korolev'] = {
  'setProtocolDebugEnabled': setProtocolDebugEnabled,
  'invokeCallback': () => showKorolevIsNotReadyMessage(),
  'swapElementInRegistry': () => showKorolevIsNotReadyMessage(),
  'ready': false
};

window.document.addEventListener("DOMContentLoaded", () => {

  let reconnect = true
  let config = window['kfg'];
  let clw = new ConnectionLostWidget(config['clw']);
  let connection = new Connection(
    config['sid'],
    config['r'],
    window.location,
    config
  );

  window['Korolev']['disconnect'] = (reconnect = false) => {
    connection.disconnect(reconnect);
  }

  window['Korolev']['connect'] = () => connection.connect();

  connection.dispatcher.addEventListener('open', () => {

    let bridge = new Bridge(config, connection);
    let globalObject = window['Korolev']

    globalObject['swapElementInRegistry'] = (a, b) => bridge._korolev.swapElementInRegistry(a, b);
    globalObject['element'] = (id) => bridge._korolev.element(id);
    globalObject['invokeCallback'] = (name, arg) => bridge._korolev.invokeCustomCallback(name, arg);

    let closeHandler = (event) => {
      bridge.destroy();
      clw.show();
      globalObject['ready'] = false;
      connection
        .dispatcher
        .removeEventListener('close', closeHandler);
    };
    connection
      .dispatcher
      .addEventListener('close', closeHandler);
  });

  connection.dispatcher.addEventListener('ready', () => {
    clw.hide();
    window['Korolev']['ready'] = true;
    window.dispatchEvent(new Event('KorolevReady'));
  });

  connection.connect();
});
