package com.autoedit;

import com.autoedit.export.StorageGuard;import org.junit.Test;import static org.junit.Assert.*;

public class StorageGuardTest { @Test public void estimatesBytes(){ assertTrue(StorageGuard.estimateBytes(30f,8_000_000) > 25_000_000); } }
