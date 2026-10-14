plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_cpp")
    dynamicDelivery { deliveryType.set("on-demand") }
}
