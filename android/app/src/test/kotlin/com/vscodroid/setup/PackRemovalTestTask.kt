package com.vscodroid.setup

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor

internal fun completedPackRemoval(): Task<Void> {
    val task = mockk<Task<Void>>()
    every { task.addOnSuccessListener(any()) } returns task
    every { task.addOnFailureListener(any()) } returns task
    every { task.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<Void>>()) } answers {
        val executor = firstArg<Executor>()
        val listener = secondArg<OnCompleteListener<Void>>()
        executor.execute { listener.onComplete(task) }
        task
    }
    return task
}
