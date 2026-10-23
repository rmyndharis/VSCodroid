/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

// layout.ts at 04c0d99f4fb0d8afe6ce4f0c58e31e183ac3e4b1 with patch 0033.
// First source line: 2449
	setPanelPosition(position: Position): void {
		if (!this.isVisible(Parts.PANEL_PART)) {
			this.setPanelHidden(false);
		}

		const panelPart = this.getPart(Parts.PANEL_PART);
		const oldPositionValue = positionToString(this.getPanelPosition());
		const newPositionValue = positionToString(position);

		// Adjust CSS
		const panelContainer = assertReturnsDefined(panelPart.getContainer());
		panelContainer.classList.remove(oldPositionValue);
		panelContainer.classList.add(newPositionValue);
		this.mainContainer.classList.remove(`panel-position-${oldPositionValue}`);
		this.mainContainer.classList.add(`panel-position-${newPositionValue}`);

		// Update Styles
		panelPart.updateStyles();

		// Layout
		const size = this.workbenchGrid.getViewSize(this.panelPartView);
		const sideBarSize = this.workbenchGrid.getViewSize(this.sideBarPartView);
		const auxiliaryBarSize = this.workbenchGrid.getViewSize(this.auxiliaryBarPartView);

		let editorHidden = !this.isVisible(Parts.EDITOR_PART, mainWindow);

		// Save last non-maximized size for panel before move
		if (newPositionValue !== oldPositionValue && !editorHidden) {

			// Save the current size of the panel for the new orthogonal direction
			// If moving down, save the width of the panel
			// Otherwise, save the height of the panel
			if (isHorizontal(position)) {
				this.stateModel.setRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_WIDTH, size.width);
			} else if (isHorizontal(positionFromString(oldPositionValue))) {
				this.stateModel.setRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT, size.height);
			}
		}

		if (isHorizontal(position) && this.getPanelAlignment() !== 'center' && editorHidden) {
			this.toggleMaximizedPanel();
			editorHidden = false;
		}

		this.stateModel.setRuntimeValue(LayoutStateKeys.PANEL_POSITION, position);

		const sideBarVisible = this.isVisible(Parts.SIDEBAR_PART);
		const auxiliaryBarVisible = this.isVisible(Parts.AUXILIARYBAR_PART);

		const hadFocus = this.hasFocus(Parts.PANEL_PART);

		if (position === Position.BOTTOM) {
			this.workbenchGrid.moveView(this.panelPartView, editorHidden ? size.height : this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT), this.editorPartView, Direction.Down);
		} else if (position === Position.TOP) {
			this.workbenchGrid.moveView(this.panelPartView, editorHidden ? size.height : this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT), this.editorPartView, Direction.Up);
		} else if (position === Position.RIGHT) {
			this.workbenchGrid.moveView(this.panelPartView, editorHidden ? size.width : this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_WIDTH), this.editorPartView, Direction.Right);
		} else {
			this.workbenchGrid.moveView(this.panelPartView, editorHidden ? size.width : this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_WIDTH), this.editorPartView, Direction.Left);
		}

		if (hadFocus) {
			this.focusPart(Parts.PANEL_PART);
		}

		// Reset sidebar to original size before shifting the panel
		this.workbenchGrid.resizeView(this.sideBarPartView, sideBarSize);
		if (!sideBarVisible) {
			this.setSideBarHidden(true);
		}

		this.workbenchGrid.resizeView(this.auxiliaryBarPartView, auxiliaryBarSize);
		if (!auxiliaryBarVisible) {
			this.setAuxiliaryBarHidden(true);
		}

		if (isHorizontal(position)) {
			this.adjustPartPositions(this.getSideBarPosition(), this.getPanelAlignment(), position);
		}

		this._onDidChangePanelPosition.fire(newPositionValue);
	}
