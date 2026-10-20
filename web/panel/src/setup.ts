import "@testing-library/jest-dom/vitest";

// jsdom (Node 18) não expõe WebCrypto: usa o do Node, que é o mesmo algoritmo do navegador.
import { webcrypto } from "node:crypto";
if (!globalThis.crypto?.subtle) Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
