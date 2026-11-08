plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_codex")
    dynamicDelivery { deliveryType.set("on-demand") }
}
