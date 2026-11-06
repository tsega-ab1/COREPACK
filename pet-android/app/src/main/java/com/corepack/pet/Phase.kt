package com.corepack.pet

/** What the pet is doing right now. Set by the chat/agent code through [PetBus]. */
enum class Phase { IDLE, QUEUED, THINKING, TOOL, TALKING, SETTLE, ERROR, OFFLINE, THROTTLED, ALERT }
