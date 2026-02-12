plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_go")
    dynamicDelivery { deliveryType.set("on-demand") }
}
