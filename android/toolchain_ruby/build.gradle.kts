plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_ruby")
    dynamicDelivery { deliveryType.set("on-demand") }
}
