const vscode = require("vscode");

function activate(context) {
    console.log("Test extension activated");
}

function deactivate() {
    console.log("Test extension deactivated");
}

module.exports = { activate, deactivate };
