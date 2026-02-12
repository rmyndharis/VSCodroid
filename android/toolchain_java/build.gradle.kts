plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_java")
    dynamicDelivery { deliveryType.set("on-demand") }
}
