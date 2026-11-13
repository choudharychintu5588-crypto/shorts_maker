package com.shortsmaker.proof

/** An error whose message is safe and understandable to show to the user. */
class ProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CancelledException : Exception("Cancelled")
