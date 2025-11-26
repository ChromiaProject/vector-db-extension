package net.postchain.gtx.extensions.vectordb.helpers

class SentenceTransformationMiniLMContainer : TextEmbeddingsInferenceContainer() {
    init {
        withCommand("--model-id", "sentence-transformers/all-MiniLM-L6-v2")
    }
}
