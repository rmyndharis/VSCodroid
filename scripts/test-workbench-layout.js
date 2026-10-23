#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
function source(number) {
  const file = fs.readdirSync(path.join(root, 'patches')).find(name => name.startsWith(number + '-'));
  return fs.readFileSync(path.join(root, 'patches', file), 'utf8').split('\n')
    .filter(line => (line.startsWith('+') && !line.startsWith('+++')) || line.startsWith(' '))
    .map(line => line.slice(1)).join('\n');
}
function body(text, declaration) {
  const start = text.indexOf(declaration);
  assert.ok(start >= 0, `Missing ${declaration}`);
  const open = text.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '{') depth++;
    if (text[i] === '}' && --depth === 0) return text.slice(open, i + 1);
  }
  assert.fail(`Unclosed ${declaration}`);
}
const Parts = Object.fromEntries(['TITLEBAR', 'STATUSBAR', 'PANEL', 'EDITOR'].map(name => [name + '_PART', name]));
const LayoutStateKeys = new Proxy({}, { get: (_, name) => name });
const mainWindow = {};
const context = {
  assert, Parts, LayoutStateKeys, mainWindow, isAndroid: true,
  SHORT_WINDOW_HEIGHT: 200, SHORT_WINDOW_RELEASE_HEIGHT: 240,
  shouldShowCustomTitleBar: () => true,
  isHorizontal: position => position === 'bottom' || position === 'top',
  ViewContainerLocation: { Panel: 'panel' }, LayoutClasses: { PANEL_HIDDEN: 'nopanel' },
};
function method(text, name, args = '') {
  const code = body(text, 'private ' + name + '(').replace(/let height: number \| undefined;/g, 'let height;');
  return vm.runInNewContext(`(function(${args}) ${code})`, context);
}
function fixture() {
  const state = { PANEL_HIDDEN: false, STATUSBAR_HIDDEN: false };
  return {
    shortWindow: false, panelYielded: false, position: 'bottom', focused: false, editorVisible: true,
    auxiliaryMaximized: false, state, title: true, status: true, panel: true,
    stateModel: { getRuntimeValue: key => state[key], setRuntimeValue: (key, value) => { state[key] = value; } },
    workbenchGrid: { setViewVisible() {} },
    titleBarPartView: {}, panelPartView: {}, mainContainer: { classList: { toggle() {} } },
    state: { runtime: { menuBar: { toggled: false } } },
    getPanelPosition() { return this.position; },
    isVisible(part) { return part === Parts.EDITOR_PART ? this.editorVisible : !this.panelYielded && !state.PANEL_HIDDEN; },
    isAuxiliaryBarMaximized() { return this.auxiliaryMaximized; },
    hasFocus() { return this.focused; },
    paneCompositeService: { getActivePaneComposite: () => ({}) },
  };
}

function activity() {
  const text = source('0030');
  const code = body(text, 'private get drawnActionHeight()');
  const height = Number(text.match(/TOUCH_ACTION_HEIGHT = (\d+)/)[1]);
  for (const coarse of [false, true]) {
    const drawn = vm.runInNewContext(`(function() ${code})`, {
      mainWindow: { matchMedia: () => ({ matches: coarse }) }, ActivitybarPart: { TOUCH_ACTION_HEIGHT: height },
    });
    for (const actionHeight of [28, 44, 52]) {
      assert.equal(drawn.call({ actionHeight }), coarse ? Math.max(actionHeight, height) : actionHeight,
        `coarse=${coarse}, actionHeight=${actionHeight}`);
    }
  }
}

function modal() {
  const text = source('0032');
  const constraints = text.slice(text.indexOf('const minWidth ='), text.indexOf('// On first layout'));
  for (const availableHeight of [0, 122, 240, 300, 800]) {
    for (const requestedHeight of [0, 97.6, 300, 1000]) {
      const result = vm.runInNewContext(`
        let width = 328.8, height = ${requestedHeight};
        ${constraints}
        const drawnHeight = Math.max(resizableElement.minSize.height, Math.min(availableHeight, height));
        ({ height, drawnHeight });`, {
        availableHeight, effectiveMinWidth: 400, MODAL_MIN_HEIGHT: 300,
        containerDimension: { width: 411 }, resizableElement: {},
        Dimension: class { constructor(width, height) { this.width = width; this.height = height; } },
      });
      assert.ok(result.drawnHeight <= availableHeight, `modal ${result.drawnHeight}px exceeds ${availableHeight}px`);
      assert.equal(result.height, result.drawnHeight, 'position must use the height the resizable element draws');
    }
  }
}

function bars() {
  const text = source('0033');
  const update = method(text, 'updateShortWindow', 'height');
  const layout = fixture();
  layout.shouldShowTitleBar = method(text, 'shouldShowTitleBar', 'targetWindow = mainWindow');
  layout.updateStatusBarVisibility = () => {};
  for (const [height, expected] of [[800, false], [200, false], [199, true], [220, true], [239, true], [240, false], [122, true], [800, false]]) {
    update.call(layout, height);
    assert.equal(layout.shortWindow, expected, `height=${height}`);
  }
  context.isAndroid = false;
  update.call(layout, 122);
  assert.equal(layout.shortWindow, false, 'desktop retains its bars');
  context.isAndroid = true;

  layout.shortWindow = true;
  assert.equal(layout.shouldShowTitleBar(), false, 'short main window hides its title');
  assert.equal(layout.shouldShowTitleBar({}), true, 'short main window must not hide an auxiliary title');
  const titleBranch = text.match(/case Parts.TITLEBAR_PART:\s*return ([\s\S]+?);/)[1];
  const title = vm.runInNewContext(`(function(targetWindow = mainWindow) { return ${titleBranch}; })`, context);
  layout.initialized = true;
  layout.workbenchGrid.isViewVisible = () => false;
  assert.equal(title.call(layout), false, 'main title visibility follows the grid');
  assert.equal(title.call(layout, {}), true, 'auxiliary title visibility does not follow the main grid');

  // This is the unchanged 1.139.1 status visibility branch until the patch replaces it.
  const statusExpression = text.match(/case Parts.STATUSBAR_PART:\s*return ([^;]+);/)?.[1]
    ?? '!this.stateModel.getRuntimeValue(LayoutStateKeys.STATUSBAR_HIDDEN)';
  const status = vm.runInNewContext(`(function(targetWindow = mainWindow) { return ${statusExpression}; })`, context);
  for (const short of [false, true]) {
    for (const hidden of [false, true]) {
      layout.shortWindow = short;
      layout.stateModel.getRuntimeValue = () => hidden;
      assert.equal(status.call(layout), !short && !hidden, `short=${short}, status setting hidden=${hidden}`);
      assert.equal(status.call(layout, {}), !hidden, 'main-window compression must not hide auxiliary bars');
    }
  }
}

function panel() {
  const text = source('0034');
  const update = method(text, 'updatePanelYield');
  for (const short of [false, true]) for (const open of [false, true]) {
    for (const position of ['bottom', 'top', 'left', 'right']) for (const editor of [false, true]) {
      for (const auxiliary of [false, true]) for (const focused of [false, true]) {
        const layout = fixture();
        layout.shortWindow = short; layout.stateModel.getRuntimeValue = () => !open;
        layout.position = position; layout.editorVisible = editor;
        layout.auxiliaryMaximized = auxiliary; layout.focused = focused;
        update.call(layout);
        assert.equal(layout.panelYielded,
          short && open && ['top', 'bottom'].includes(position) && editor && !auxiliary && !focused,
          JSON.stringify({ short, open, position, editor, auxiliary, focused }));
      }
    }
  }

  // A panel shown before moving is squeezed by the short grid. Preserve its earlier height.
  const savedHeight = text.match(/setRuntimeValue\(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT, ([^;]+)\);/g)
    ?.map(line => line.match(/HEIGHT, (.*)\);/)[1]).find(expression => expression.includes('size.height')) ?? 'size.height';
  const height = vm.runInNewContext(`(function(size) { return ${savedHeight}; })`, context);
  assert.equal(height.call({ panelHeightBeforeShort: 203 }, { height: 77 }), 203, 'moving a yielded panel must retain its earlier height');

  for (const expression of [
    text.match(/zenModeExitInfo.wasVisible.panel = ([^;]+);/)[1],
    text.match(/panelVisible: ([^,\n]+)/)[1],
  ]) {
    const visible = vm.runInNewContext(`(function() { return ${expression}; })`, context);
    assert.equal(visible.call({ panelYielded: true, isVisible: () => false }), true,
      'zen mode and the maximized secondary sidebar must retain a yielded panel');
    assert.equal(visible.call({ panelYielded: false, isVisible: () => false }), false,
      'a panel the user closed must stay closed');
  }
}

function sash() {
  const text = source('0034');
  const guard = 'if (part === panelPart && this.panelYielded && visible)';
  const code = text.includes(guard) ? `${guard} ${body(text, guard)}` : '';
  for (const endYield of [false, true]) {
    const tasks = [], events = [], grid = { visible: true };
    const panelPart = {};
    const listener = vm.runInNewContext(`(function(part, visible) { ${code}\nevents.push(visible); })`, {
      panelPart, events, queueMicrotask: callback => tasks.push(callback),
    });
    const layout = { panelYielded: true, panelPartView: panelPart, workbenchGrid: {
      isViewVisible: () => grid.visible,
      setViewVisible: (_, visible) => { grid.visible = visible; },
    } };
    listener.call(layout, panelPart, true);
    assert.equal(events.length, 0, 'an incidental snap must not report a visible yielded panel');
    if (endYield) layout.panelYielded = false;
    for (const task of tasks) task();
    assert.equal(grid.visible, endYield, 'rehide only while the panel still yields');
  }
}

function statusToggle() {
  const text = source('0033');
  // The upstream action originally toggles rendered visibility instead of the setting.
  const visibility = (text.match(/const visibility = ([^;]+);/)?.[1]
    ?? 'layoutService.isVisible(Parts.STATUSBAR_PART, mainWindow)').replace(/<boolean>/g, '');
  const action = vm.runInNewContext(`(function(configurationService, layoutService) {
    const visibility = ${visibility}; return !visibility;
  })`, { ...context, ToggleStatusbarVisibilityAction: { statusbarVisibleKey: 'workbench.statusBar.visible' } });
  for (const savedVisibility of [true, false]) {
    assert.equal(action({ getValue: () => savedVisibility }, { isVisible: () => false }), !savedVisibility,
      'the status bar command must toggle the saved setting while the window hides the bar');
  }
}

function panelSave() {
  const expression = source('0034').match(/const panelSize = ([\s\S]+?);/)[1];
  const saved = vm.runInNewContext(`(function() { return ${expression}; })`, context);
  for (const horizontal of [false, true]) for (const closed of [false, true]) for (const yielded of [false, true]) {
    const layout = {
      panelHeightBeforeShort: 203, panelYielded: yielded, panelPartView: {},
      getPanelPosition: () => horizontal ? 'bottom' : 'right',
      stateModel: { getRuntimeValue: key => key === 'PANEL_POSITION' ? horizontal ? 'bottom' : 'right' : closed },
      workbenchGrid: { getViewCachedVisibleSize: () => horizontal ? 77 : 180, getViewSize: () => ({ height: 77, width: 180 }) },
    };
    assert.equal(saved.call(layout), horizontal ? 203 : 180,
      `saved panel size must preserve height without confusing side-panel width: ${JSON.stringify({ horizontal, closed, yielded })}`);
  }
}

const sections = { activity, modal, bars, panel, sash, 'status-toggle': statusToggle, 'panel-save': panelSave };
for (const name of process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(sections)) {
  assert.ok(sections[name], `Unknown section ${name}`);
  sections[name]();
  console.log(`${name}: passed`);
}
