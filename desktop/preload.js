const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('seven', {
  loadSettings: () => ipcRenderer.invoke('settings:load'),
  saveSettings: (settings) => ipcRenderer.invoke('settings:save', settings),
  startSpeech: () => ipcRenderer.invoke('speech:start'),
  stopSpeech: () => ipcRenderer.invoke('speech:stop'),
  sendTurn: (payload) => ipcRenderer.invoke('llm:send', payload),
  cancelTurn: () => ipcRenderer.invoke('llm:cancel'),
  testConnection: (payload) => ipcRenderer.invoke('llm:test', payload),
  onSpeech: (callback) => {
    const handler = (_event, payload) => callback(payload);
    ipcRenderer.on('speech:event', handler);
    return () => ipcRenderer.removeListener('speech:event', handler);
  },
  onLlm: (callback) => {
    const handler = (_event, payload) => callback(payload);
    ipcRenderer.on('llm:event', handler);
    return () => ipcRenderer.removeListener('llm:event', handler);
  },
});
