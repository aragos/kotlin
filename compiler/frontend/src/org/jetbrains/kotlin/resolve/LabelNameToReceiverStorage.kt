/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor

class LabelNameToReceiverStorage {
    private val storage = mutableMapOf<String, List<ReceiverParameterDescriptor>>()

    operator fun set(name: String, receiver: ReceiverParameterDescriptor) {
        storage.merge(name, listOf(receiver)) { l1, l2 -> l1 + l2 }
    }

    operator fun get(name: String) = storage[name]
}