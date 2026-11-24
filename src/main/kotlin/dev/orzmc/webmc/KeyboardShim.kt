package dev.orzmc.webmc

/**
 * 键盘兜底脚本：注入到客户端 HTML 的 `<head>` 里，修复「开了中文/日文输入法（IME）就完全打不出字」。
 *
 * 为什么需要：Eaglercraft（1.8.8 / 1.12.2 的 TeaVM 产物）**不用** DOM 文本框，而是自己在 `window` 上
 * 监听 `keydown`：字符取自 `event.key`（单字符才认），键码取自 `event.code`→键盘布局表，失败再退到
 * `event.which || event.keyCode`。输入法激活时浏览器发的是
 * `keyCode = 229` / `key = "Process"` / `isComposing = true`（`code` 仍是物理键位，例如 `Slash`）。
 * 于是客户端拿到「键位未知 + 字符不是单字符」→ 静默丢弃：菜单里打不出字、游戏里按 `/` 也开不了聊天框，
 * 连 `/login` 这种命令都没法输入。
 *
 * 做法：在捕获阶段把这类 IME 事件按**物理键位**改写成正常的按键事件（`key`/`keyCode`/`which` 齐全），
 * 再派发回客户端自己的监听器；原事件吞掉。等价于「输入法开着时，按键按英文键盘语义生效」。
 *
 * 已知边界：
 * - 只保证 ASCII 与常用控制键（等于英文输入状态下的行为）；**中文/日文文本不会进入游戏**。
 *   要打中文请切回英文输入法后直接输入 ASCII，或在游戏外复制粘贴（客户端支持 Ctrl+V）。
 * - 客户端若把自己的隐藏 `<input>` 聚焦来做文本输入（部分版本的移动/软键盘路径），脚本自动让路，
 *   不会与客户端自身处理重复上屏。
 * - 可在 `meta.json` 用 `"keyboardShim": false` 关掉（逐步排查或客户端自身已修时使用）。
 */
internal object KeyboardShim {

    /** 注入用片段（含 `<script>` 标签）；内容是 ES5，避免老浏览器/严格 CSP 下的语法问题。 */
    val script: String = """<script>
/* OrzWebMC keyboard shim: IME(keyCode 229) -> 物理键位按键事件。见 webmc/docs/client-hosting-service.md */
(function () {
  'use strict';
  if (window.__webmcKeyShim) return;
  window.__webmcKeyShim = 1;

  // code -> [无 Shift 的 key, 带 Shift 的 key, 虚拟键码(与 LWJGL 一致)]
  var MAP = {
    Backquote: ['`', '~', 192], Digit1: ['1', '!', 49], Digit2: ['2', '@', 50],
    Digit3: ['3', '#', 51], Digit4: ['4', '${'$'}', 52], Digit5: ['5', '%', 53],
    Digit6: ['6', '^', 54], Digit7: ['7', '&', 55], Digit8: ['8', '*', 56],
    Digit9: ['9', '(', 57], Digit0: ['0', ')', 48],
    Minus: ['-', '_', 189], Equal: ['=', '+', 187],
    BracketLeft: ['[', '{', 219], BracketRight: [']', '}', 221], Backslash: ['\\', '|', 220],
    Semicolon: [';', ':', 186], Quote: ["'", '"', 222],
    Comma: [',', '<', 188], Period: ['.', '>', 190], Slash: ['/', '?', 191],
    Space: [' ', ' ', 32],
    Enter: ['Enter', 'Enter', 13], NumpadEnter: ['Enter', 'Enter', 13],
    Escape: ['Escape', 'Escape', 27], Tab: ['Tab', 'Tab', 9],
    Backspace: ['Backspace', 'Backspace', 8], Delete: ['Delete', 'Delete', 46],
    Insert: ['Insert', 'Insert', 45], Home: ['Home', 'Home', 36], End: ['End', 'End', 35],
    PageUp: ['PageUp', 'PageUp', 33], PageDown: ['PageDown', 'PageDown', 34],
    ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37], ArrowUp: ['ArrowUp', 'ArrowUp', 38],
    ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowDown: ['ArrowDown', 'ArrowDown', 40],
    ShiftLeft: ['Shift', 'Shift', 16], ShiftRight: ['Shift', 'Shift', 16],
    ControlLeft: ['Control', 'Control', 17], ControlRight: ['Control', 'Control', 17],
    AltLeft: ['Alt', 'Alt', 18], AltRight: ['Alt', 'Alt', 18],
    MetaLeft: ['Meta', 'Meta', 91], MetaRight: ['Meta', 'Meta', 92],
    CapsLock: ['CapsLock', 'CapsLock', 20]
  };
  var i, c;
  for (i = 0; i < 26; i++) {
    c = String.fromCharCode(65 + i);
    MAP['Key' + c] = [c.toLowerCase(), c, 65 + i];
  }
  for (i = 1; i <= 12; i++) MAP['F' + i] = ['F' + i, 'F' + i, 111 + i];

  function isEditable(el) {
    if (!el || !el.tagName) return false;
    var tag = el.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
  }

  function isImeEvent(e) {
    return e.keyCode === 229 || e.isComposing === true || e.key === 'Process' || e.key === 'Unidentified';
  }

  function redispatch(e, key, code) {
    var ev;
    try {
      ev = new KeyboardEvent(e.type, {
        key: key, code: e.code, location: e.location || 0, repeat: !!e.repeat, isComposing: false,
        bubbles: true, cancelable: true,
        shiftKey: !!e.shiftKey, ctrlKey: !!e.ctrlKey, altKey: !!e.altKey, metaKey: !!e.metaKey
      });
    } catch (err) {
      return false;
    }
    // keyCode/which 是旧属性，构造函数里设不了；客户端会读它们，这里覆盖成实例属性。
    try {
      Object.defineProperty(ev, 'keyCode', { get: function () { return code; } });
      Object.defineProperty(ev, 'which', { get: function () { return code; } });
      Object.defineProperty(ev, 'charCode', { get: function () { return 0; } });
    } catch (err2) {}
    (e.target || document.body || document).dispatchEvent(ev);
    return true;
  }

  function onKey(e) {
    if (!isImeEvent(e) || !e.code) return;
    var m = MAP[e.code];
    if (!m) return;
    if (isEditable(document.activeElement)) return;
    if (!redispatch(e, e.shiftKey ? m[1] : m[0], m[2])) return;
    e.preventDefault();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation(); else e.stopPropagation();
  }

  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);
})();
</script>"""
}
