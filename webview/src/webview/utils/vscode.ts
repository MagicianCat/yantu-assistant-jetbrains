import type { WebviewMessage } from "../../shared/messages";

interface YantuVsCodeApi { postMessage: (message: WebviewMessage) => void; }
declare const acquireVsCodeApi: (() => YantuVsCodeApi) | undefined;
declare global { interface Window { yantuHost?: { postMessage: (message: WebviewMessage) => void }; yantuManagedWindows?: boolean; } }

export const hasVscodeApi = typeof acquireVsCodeApi !== "undefined";

const noopVscode = {
  postMessage: (_message: WebviewMessage) => {},
};

export const vscode = window.yantuHost ?? (hasVscodeApi ? acquireVsCodeApi!() : noopVscode);
