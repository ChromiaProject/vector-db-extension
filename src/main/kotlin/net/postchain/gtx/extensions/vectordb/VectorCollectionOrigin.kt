package net.postchain.gtx.extensions.vectordb

enum class VectorCollectionOrigin {
    /** Blockchain configuration */
    STATIC,

    /** Dynamically created by dapp code */
    DYNAMIC
}
