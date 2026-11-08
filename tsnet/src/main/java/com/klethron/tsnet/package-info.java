/// Embedded tsnet nodes with explicitly owned lifetimes.
///
/// Reference types are non-null unless annotated otherwise. Results must be used: an ignored
/// [TsnetNode] can never be closed.
@NullMarked
@CheckReturnValue
package com.klethron.tsnet;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NullMarked;
