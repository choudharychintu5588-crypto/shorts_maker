// Pure C++ core (no JNI) so it can be unit-tested on a normal computer.
#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct whisper_context;

struct TokenPiece {
    std::string text;   // raw bytes of the token (may be a fragment of a UTF-8 character)
    int64_t t0_ms;
    int64_t t1_ms;
    bool special;
};

struct WordOut {
    std::string text;
    int64_t t0_ms;
    int64_t t1_ms;
};

// Joins token fragments into words. A new word starts at every token whose text starts with a space.
// Invalid UTF-8 is replaced by U+FFFD so the result is always safe to hand to Java/Kotlin.
std::vector<WordOut> build_words(const std::vector<TokenPiece> & tokens);

std::string sanitize_utf8(const std::string & in);
std::string json_escape(const std::string & in);

typedef void (*ProgressFn)(int percent, void * user);
typedef bool (*AbortFn)(void * user);

// Reads 16 kHz mono signed 16-bit little-endian PCM from `pcm_path`, runs Whisper and returns JSON:
// {"language":"hi","segments":[{"t0":0,"t1":3200,"text":"..","words":[{"w":"..","t0":0,"t1":300}]}]}
// On failure returns an empty string and fills `err`.
std::string transcribe_pcm16_file(whisper_context * ctx, const std::string & pcm_path, int threads,
                                  const std::string & language, bool translate,
                                  ProgressFn progress, AbortFn abort_fn, void * user, std::string & err);
