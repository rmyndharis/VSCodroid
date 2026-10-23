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
const Parts = Object.fromEntries(['TITLEBAR', 'STATUSBAR', 'PANEL', 'EDITOR', 'SIDEBAR', 'AUXILIARYBAR'].map(name => [name + '_PART', name]));
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

function patchedPanelPosition() {
  const fixture = fs.readFileSync(path.join(__dirname, 'fixtures/workbench-panel-position.ts'), 'utf8');
  const firstLine = Number(fixture.match(/First source line: (\d+)/)[1]);
  const original = fixture.slice(fixture.indexOf('\tsetPanelPosition(')).trimEnd().split('\n');
  const added = new Map(), removed = new Set();
  let line;
  for (const entry of fs.readFileSync(path.join(root, 'patches/0034-short-window-yields-the-panel.patch'), 'utf8').split('\n')) {
    const hunk = entry.match(/^@@ -(\d+)/);
    if (hunk) { line = Number(hunk[1]); continue; }
    if (line === undefined || ![' ', '+', '-'].includes(entry[0])) continue;
    const index = line - firstLine;
    if (index >= 0 && index < original.length) {
      if (entry[0] === '+') added.set(index, [...(added.get(index) ?? []), entry.slice(1)]);
      else {
        assert.equal(original[index], entry.slice(1), `Panel position fixture context at source line ${line}`);
        if (entry[0] === '-') removed.add(index);
      }
    }
    if (entry[0] !== '+') line++;
  }
  const patched = original.flatMap((entry, index) => [...(added.get(index) ?? []), ...(removed.has(index) ? [] : [entry])]).join('\n');
  return vm.runInNewContext(`(function(position) ${body(patched, 'setPanelPosition(')})`, {
    ...context, Position: { TOP: 'top', BOTTOM: 'bottom', LEFT: 'left', RIGHT: 'right' },
    Direction: { Up: 'up', Down: 'down', Left: 'left', Right: 'right' },
    positionToString: position => position, positionFromString: position => position, assertReturnsDefined: value => value,
  });
}

function panelRoundTrip() {
  const text = source('0034');
  const setPanelPosition = patchedPanelPosition();
  function create(focusOrder) {
    const layout = fixture();
    const state = layout.stateModel;
    const values = {
      PANEL_HIDDEN: false, PANEL_POSITION: 'bottom', PANEL_LAST_NON_MAXIMIZED_HEIGHT: 300,
      PANEL_LAST_NON_MAXIMIZED_WIDTH: 292,
    };
    state.getRuntimeValue = key => values[key];
    state.setRuntimeValue = (key, value) => { values[key] = value; };
    Object.assign(layout, {
      _mainContainerDimension: { height: 428 },
      getPanelPosition: () => values.PANEL_POSITION, getPanelAlignment: () => 'center', getSideBarPosition: () => 'left',
      getPart: () => ({ getContainer: () => ({ classList: { add() {}, remove() {} } }), updateStyles() {} }),
      isVisible: part => part === Parts.EDITOR_PART || (part === Parts.PANEL_PART && !layout.panelYielded && !values.PANEL_HIDDEN),
      isPanelMaximized: () => false, setSideBarHidden() {}, setAuxiliaryBarHidden() {}, adjustPartPositions() {},
      updateStatusBarVisibility() {}, shouldShowTitleBar: () => !layout.shortWindow,
      _onDidChangePanelPosition: { fire() {} }, setPanelPosition,
      updateShortWindow: method(text, 'updateShortWindow', 'height, afterGridLayout = false'),
      rememberPanelHeight: method(text, 'rememberPanelHeight'), restorePanelHeight: method(text, 'restorePanelHeight'),
      updatePanelYield: method(text, 'updatePanelYield'),
    });
    layout.mainContainer.classList.add = layout.mainContainer.classList.remove = () => {};
    const grid = {
      visible: true, height: 176, width: 915, cached: 176,
      maxHeight: () => layout._mainContainerDimension.height < 200 ? 113 : 365,
      getViewSize: view => view === layout.panelPartView ? { height: grid.visible ? grid.height : 0, width: grid.width } : { height: 0, width: 0 },
      getViewCachedVisibleSize: () => grid.cached,
      setViewVisible(view, visible) {
        if (view !== layout.panelPartView || visible === grid.visible) return;
        if (visible) grid.height = Math.min(grid.cached, grid.maxHeight());
        else grid.cached = grid.height;
        grid.visible = visible;
      },
      moveView(view, requested) {
        assert.equal(view, layout.panelPartView);
        if (context.isHorizontal(values.PANEL_POSITION)) { grid.height = Math.min(requested, grid.maxHeight()); grid.width = 915; }
        else { grid.width = requested; grid.height = layout._mainContainerDimension.height - 8; }
      },
      resizeView(view, size) { if (view === layout.panelPartView) { grid.height = Math.min(size.height, grid.maxHeight()); grid.width = size.width; } },
    };
    layout.workbenchGrid = grid;
    layout.setPanelHidden = hidden => {
      layout.panelYielded = false;
      values.PANEL_HIDDEN = hidden;
      grid.setViewVisible(layout.panelPartView, !hidden);
      if (!layout.shortWindow && !hidden) layout.restorePanelHeight();
    };
    function resize(height) {
      layout._mainContainerDimension.height = height;
      layout.updateShortWindow(height);
      if (grid.visible) grid.height = context.isHorizontal(values.PANEL_POSITION) ? Math.min(grid.height, grid.maxHeight()) : height - 8;
      layout.updateShortWindow(height, true);
    }
    layout.focusPart = () => { layout.focused = true; if (focusOrder === 'inside') resize(428); };
    function command(position) {
      if (focusOrder === 'inside') layout.focused = true;
      layout.setPanelPosition(position);
      if (context.isHorizontal(position) && layout.shortWindow) {
        assert.equal(grid.height, 113, 'moving into the short grid squeezes the requested height');
        assert.equal(layout.panelHeightBeforeShort, 176, 'retain the target height until the focus resize');
        assert.equal(layout.panelYielded, true, 'an editor-focused horizontal panel yields until focus resizes the window');
      }
      layout.focused = true;
      resize(428);
    }
    return { layout, values, grid, resize, command };
  }
  for (const side of ['right', 'left']) for (const horizontal of ['bottom', 'top']) for (const focusOrder of ['after', 'inside']) {
    const { layout, values, grid, resize, command } = create(focusOrder);
    resize(191);
    assert.equal(layout.panelYielded, true);
    command(side);
    assert.equal(values.PANEL_LAST_NON_MAXIMIZED_HEIGHT, 176, 'moving to a side preserves the previous horizontal height');
    assert.equal(layout.panelHeightBeforeShort, undefined, 'a tall side panel clears the horizontal restoration');
    layout.focused = false;
    resize(191);
    assert.equal(grid.height, 183);
    command(horizontal);
    assert.equal(grid.height, 176, `${side} -> ${horizontal}, focus ${focusOrder}: restore the height after the grid squeezed it to 113`);
    assert.equal(values.PANEL_LAST_NON_MAXIMIZED_WIDTH, 292, 'the side panel retains its own width');
    assert.equal(layout.panelHeightBeforeShort, undefined, 'restoring consumes the saved height');
  }
  const tall = create('after');
  tall.layout.setPanelPosition('right');
  tall.layout.setPanelPosition('bottom');
  assert.equal(tall.layout.panelHeightBeforeShort, undefined, 'a tall move must not create a pending restoration');
  const remembered = create('after');
  remembered.resize(191);
  remembered.values.PANEL_LAST_NON_MAXIMIZED_HEIGHT = 90;
  remembered.layout.setPanelPosition('top');
  assert.equal(remembered.layout.panelHeightBeforeShort, 176, 'a short move must keep an earlier unsqueezed height');
}

const sections = { activity, modal, bars, panel, sash, 'status-toggle': statusToggle, 'panel-save': panelSave, 'panel-round-trip': panelRoundTrip };
for (const name of process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(sections)) {
  assert.ok(sections[name], `Unknown section ${name}`);
  sections[name]();
  console.log(`${name}: passed`);
}
