// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.generation;

public record SetterGetterGenerationOptions(boolean copyAllAnnotations) {
  public static SetterGetterGenerationOptions empty() {
    return new SetterGetterGenerationOptions(false);
  }
}