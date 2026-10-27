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

function activityLifecycle() {
  const text = source('0030');
  const constants = Object.fromEntries([...text.matchAll(/static readonly (\w+) = (\d+);/g)].map(match => [match[1], Number(match[2])]));
  let coarse = false;
  const listeners = new Set(), disposables = [], created = [];
  const media = {
    get matches() { return coarse; },
    addEventListener(type, listener) { assert.equal(type, 'change'); listeners.add(listener); },
    removeEventListener(type, listener) { assert.equal(type, 'change'); listeners.delete(listener); },
  };
  const scope = {
    ActivitybarPart: constants, ActivityBarCompositeBar: class {},
    mainWindow: { matchMedia: query => { assert.equal(query, '(pointer: coarse)'); return media; } },
    ActionsOrientation: { VERTICAL: 0 }, Parts: { ACTIVITYBAR_PART: 'activitybar' },
    Position: { LEFT: 0 }, HoverPosition: { RIGHT: 1, LEFT: 0 },
    clearNode: content => { content.children.length = 0; },
    addDisposableListener(target, type, listener) {
      target.addEventListener(type, listener);
      return { dispose: () => target.removeEventListener(type, listener) };
    },
  };
  const compile = declaration => vm.runInNewContext(`(function() ${body(text, declaration)
    .replace(/: IColorTheme/g, '').replace(/e\?: MouseEvent \| GestureEvent/g, 'e')})`, scope);
  const part = {
    _isCompact: true, actionHeight: 28, actionGap: 0, location: 'sidebar', paneCompositePart: {},
    content: { children: [] }, dimension: { width: 36, height: 200 },
    layoutService: { getSideBarPosition: () => 0 },
    compositeBar: { value: undefined, clear() { this.value?.dispose(); this.value = undefined; } },
    _register(disposable) { disposables.push(disposable); return disposable; },
    layout(width, height) { this.compositeBar.value?.layout(width, height); },
    instantiationService: { createInstance(_, location, options, kind, pane, globalActivities) {
      const value = { options, globalActivities, disposed: false, dispose() { this.disposed = true; }, create() {}, layout() {} };
      created.push(value);
      return value;
    } },
  };
  Object.defineProperty(part, 'drawnActionHeight', { get: compile('private get drawnActionHeight()').bind(part) });
  Object.defineProperty(part, 'compositeSize', { get: compile('private get compositeSize()').bind(part) });
  part.createCompositeBar = compile('private createCompositeBar(');
  part.recreateCompositeBar = compile('private recreateCompositeBar(');
  part.compositeBar.value = part.createCompositeBar();
  const registration = text.split('\n').find(line => line.includes("addDisposableListener(mainWindow.matchMedia('(pointer: coarse)'), 'change'"));
  if (registration) vm.runInNewContext(`(function() { ${registration} })`, scope).call(part);
  const sizes = () => {
    const value = part.compositeBar.value;
    return [value.options.compositeSize, value.options.overflowActionSize, value.globalActivities.actionHeight];
  };
  assert.deepEqual(sizes(), [28, 28, 28]);
  for (const [next, expected] of [[true, 44], [false, 28]]) {
    const previous = part.compositeBar.value;
    coarse = next;
    for (const listener of listeners) listener({ matches: coarse });
    assert.deepEqual(sizes(), [expected, expected, expected]);
    assert.equal(previous.disposed, true);
    assert.equal(listeners.size, 1, 'recreation must not duplicate the media subscription');
  }
  part.compositeBar.clear();
  coarse = true;
  const count = created.length;
  for (const listener of listeners) listener({ matches: coarse });
  assert.equal(created.length, count, 'a hidden bar stays lazy');
  part.compositeBar.value = part.createCompositeBar();
  assert.deepEqual(sizes(), [44, 44, 44], 'showing it uses the current pointer');
  disposables.forEach(disposable => disposable.dispose());
  assert.equal(listeners.size, 0, 'disposing the owner removes the media subscription');
}

function activityOverflow() {
  const text = source('0008');
  const code = body(text, 'private updateCompositeSwitcher(')
    .replace(/ as HTMLElement/g, '').replace(/: number\[\]/g, '').replace(/: number \| undefined/g, '')
    .replace(/([)\]])!(?=[;,.\]])/g, '$1').replace(/\b(\w+)!(?=[),;.\]])/g, '$1');
  function run({ height, item = 44, gap = 8, inset = 3, global = 96, horizontal = false, active, count = 2, hidden = false }) {
    const style = (marginTop, marginBottom, display = 'block') => ({
      marginTop: String(marginTop), marginBottom: String(marginBottom), display,
      getPropertyValue: name => name === '--activity-bar-action-gap' ? String(gap) : '',
    });
    const bar = { style: style(inset, 900) };
    const sibling = { offsetHeight: global, style: style(0, inset, hidden ? 'none' : 'block') };
    bar.parentElement = { clientHeight: height, children: [bar, sibling] };
    const scope = {
      document: { querySelector: () => bar }, getComputedStyle: node => node.style,
      ActionsOrientation: { VERTICAL: 0 }, CompositeOverflowActivityAction: class {},
      CompositeOverflowActivityActionViewItem: class {},
    };
    const items = Array.from({ length: count }, (_, index) => ({ id: String(index), pinned: true, activityAction: { id: String(index) } }));
    const actions = [];
    const model = { visibleItems: items, activeItem: items.find(item => item.id === active), findItem: id => items.find(item => item.id === id) };
    const owner = {
      model, dimension: { width: 2 * (item + gap), height: 2 * (item + gap) },
      options: { orientation: horizontal ? 1 : 0, overflowActionSize: item + gap },
      compositeSizeInBar: new Map(items.map(value => [value.id, item + gap])), visibleComposites: [],
      compositeOverflowAction: {}, compositeOverflowActionViewItem: {},
      compositeSwitcherBar: { push(action, options) { actions.splice(options?.index ?? actions.length, 0, action); }, pull(index) { actions.splice(index, 1); }, length: () => actions.length },
      instantiationService: { createInstance: () => ({}) }, _onDidChange: { fire() {} },
    };
    vm.runInNewContext(`(function(donotTrigger) ${code})`, scope).call(owner, true);
    return { visible: owner.visibleComposites, overflow: !!owner.compositeOverflowAction.value };
  }
  for (const fixture of [
    { height: 198 },
    { height: 166, item: 36, global: 80 },
    { height: 186, gap: 4, inset: 1, global: 92 },
    { height: 154, item: 36, gap: 4, inset: 1, global: 76 },
    { height: 182, gap: 0, global: 88 },
    { height: 192, item: 48, gap: 0, inset: 0, global: 96 },
    { height: 146, global: 44 },
    { height: 99, hidden: true },
  ]) {
    const result = run(fixture);
    assert.deepEqual(result.visible, ['0', '1'], JSON.stringify(fixture));
    assert.equal(result.overflow, false, JSON.stringify(fixture));
  }
  for (const height of [197, 196]) {
    const result = run({ height });
    assert.deepEqual(result.visible, [], `height=${height} cannot fit an item and overflow with their insets`);
    assert.equal(result.overflow, true);
  }
  assert.deepEqual(run({ height: 1, horizontal: true }).visible, ['0', '1'], 'horizontal bars keep the width budget');
  assert.deepEqual(run({ height: 198, count: 3, active: '2' }).visible, ['2'], 'overflow admission still reserves the active item');
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

function panelMinimumWidth() {
  const text = source('0041');
  for (const android of [false, true]) {
    const minimum = vm.runInNewContext(`(function() ${body(text, 'get minimumWidth()')})`, {
      isAndroid: android, isHorizontal: position => position === 'top' || position === 'bottom',
    });
    for (const position of ['top', 'bottom', 'left', 'right']) {
      for (const [available, expected] of [[800, 300], [300, 300], [299, 149.5], [261, 130.5], [91, 45.5], [0, 0], [-79, 0]]) {
        const adaptive = android && (position === 'top' || position === 'bottom');
        const mainContainer = {};
        const panel = { layoutService: {
          mainContainer, getPanelPosition: () => position,
          getMaximumEditorDimensions(container) {
            assert.ok(adaptive, 'vertical panels must not recurse through their own minimum');
            assert.equal(container, mainContainer);
            return { width: available };
          },
        } };
        assert.equal(minimum.call(panel), adaptive ? expected : 300, `${android}/${position}/${available}`);
      }
    }
  }
}

function terminalRanges() {
  const text = source('0040');
  const highlight = vm.runInNewContext(`(function(range) ${body(text, 'private _highlightBufferRange(')
    .replace('let renderedElement: HTMLElement | undefined;', 'let renderedElement;')})`);
  const cases = [
    ['single line', [4, 2, 12, 2], [[1, 3, 9]]],
    ['single cell', [40, 2, 40, 2], [[1, 39, 1]]],
    ['wrapped link', [36, 2, 17, 3], [[1, 35, 5], [2, 0, 17]]],
    ['wrapped increasing columns', [4, 2, 12, 3], [[1, 3, 37], [2, 0, 12]]],
    ['three rows', [36, 2, 17, 4], [[1, 35, 5], [2, 0, 40], [3, 0, 17]]],
    ['history rows', [1, 2, 40, 4], [[1, 0, 40], [2, 0, 40], [3, 0, 40]]],
    ['trimmed final colon', [36, 2, 0, 3], [[1, 35, 5]]],
  ];
  for (const [name, [startX, startY, endX, endY], expected] of cases) {
    const decorations = [], actual = [];
    let reset = 0;
    const addon = {
      _navigationDecorations: [],
      _resetNavigationDecorations() { reset++; },
      _createMarkerForOffset(line, offset) { return { line: line + offset }; },
      _terminal: { cols: 40, registerDecoration({ marker, x, width }) {
        assert.ok(Number.isInteger(x) && x >= 0, `${name}: invalid start column`);
        assert.ok(Number.isInteger(width) && width > 0 && x + width <= 40, `${name}: invalid width ${width}`);
        actual.push([marker.line, x, width]);
        let dispose;
        const decoration = { onRender() {}, onDispose(callback) { dispose = callback; }, dispose() { dispose(); } };
        decorations.push(decoration);
        return decoration;
      } },
    };
    highlight.call(addon, { start: { x: startX, y: startY }, end: { x: endX, y: endY } });
    assert.deepEqual(actual, expected, name);
    assert.equal(reset, 1, `${name}: clear the previous preview`);
    assert.equal(addon._navigationDecorations.length, expected.length, name);
    for (const decoration of decorations) decoration.dispose();
    assert.equal(addon._navigationDecorations.length, 0, `${name}: forget disposed highlights`);
  }
  highlight.call({ _terminal: undefined, _resetNavigationDecorations() { assert.fail('missing terminal must be a no-op'); } }, {});
}

const sections = { activity, 'activity-lifecycle': activityLifecycle, 'activity-overflow': activityOverflow, modal, bars, panel, sash, 'status-toggle': statusToggle, 'panel-save': panelSave, 'panel-round-trip': panelRoundTrip, 'panel-minimum-width': panelMinimumWidth, 'terminal-ranges': terminalRanges };
for (const name of process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(sections)) {
  assert.ok(sections[name], `Unknown section ${name}`);
  sections[name]();
  console.log(`${name}: passed`);
}
