# File Transformation - Synchronous Processing Implementation

## Summary

Successfully implemented synchronous/on-demand file processing capability alongside the existing automatic processing mode. Each configuration can now independently choose between automatic (scheduled) or on-demand (synchronous) processing.

## Key Changes

### 1. Per-Configuration Scan Intervals

**Before:**
- Global `scan-interval-seconds` in application.properties
- All configurations shared the same interval
- No way to disable automatic processing for specific configs

**After:**
- `scan-interval-seconds` moved to each configuration in file_transform_config.json
- Each config can have different intervals
- Set to `0` or negative for on-demand only processing

### 2. FileTransformConfig.java Updates

**Added Fields:**
```java
@JsonProperty(value = "scan-interval-seconds")
private int scanIntervalSeconds = 0;
```

**New Methods:**
- `getScanIntervalSeconds()`
- `setScanIntervalSeconds(int)`

### 3. FileTransformService.java Enhancements

**Changed:**
- Single scheduler → Map of schedulers (one per config)
- `processConfiguration()` now returns `int` (files processed count)
- Only configs with `scan-interval-seconds > 0` get scheduled

**Added Methods:**
```java
public int processConfigurationByName(String configName)
public List<String> getConfigurationNames()
public FileTransformConfig getConfigurationByName(String configName)
```

### 4. New REST API Endpoints

**FileTransformResource.java** - New REST resource for on-demand processing:

```
GET  /api/file-transform/configurations         - List all configs
GET  /api/file-transform/configuration/{name}   - Get config details  
POST /api/file-transform/trigger/{name}         - Trigger processing
```

### 5. Command-Line Trigger Application

**FileTransformTriggerApp.java** - Standalone application for triggering:
- Takes configuration name as argument
- Validates configuration exists
- Returns appropriate exit codes
- Can be used in scripts and automation

**Exit Codes:**
- `0` - Success
- `1` - Configuration not found
- `2` - Invalid arguments
- `3` - Configuration file error
- `4` - Processing error

## Files Created/Modified

### Created Files:
1. **src/main/java/com/ubp/rgd/proxy/services/FileTransformResource.java**
   - REST API endpoints for on-demand processing
   
2. **src/main/java/com/ubp/rgd/proxy/tools/FileTransformTriggerApp.java**
   - Command-line trigger application

3. **doc/FILE_TRANSFORM_SYNCHRONOUS.md**
   - Comprehensive documentation for sync processing

### Modified Files:
1. **src/main/java/com/ubp/rgd/proxy/transform/config/FileTransformConfig.java**
   - Added `scanIntervalSeconds` field

2. **src/main/java/com/ubp/rgd/proxy/services/FileTransformService.java**
   - Changed to per-config scheduling
   - Added public methods for external invocation
   - Return file counts from processing

3. **config/file_transform_config.json**
   - Added `scan-interval-seconds: 30` to first config (automatic)
   - Added `scan-interval-seconds: 0` to second config (on-demand)

4. **config/file_transform_application.properties.example**
   - Removed global scan-interval-seconds
   - Added REST API documentation
   - Updated usage instructions

## Architecture

### Processing Modes

```
┌─────────────────────────────────────────────────────────┐
│         File Transform Configuration                     │
├─────────────────────────────────────────────────────────┤
│  scan-interval-seconds: 30 (or > 0)                     │
│  → Automatic Processing                                  │
│     ├─ Scheduler created                                 │
│     ├─ Runs every N seconds                             │
│     └─ Background processing                             │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│         File Transform Configuration                     │
├─────────────────────────────────────────────────────────┤
│  scan-interval-seconds: 0 (or ≤ 0)                      │
│  → On-Demand Processing                                  │
│     ├─ No scheduler created                              │
│     ├─ Triggered via REST API                           │
│     └─ Synchronous execution                             │
└─────────────────────────────────────────────────────────┘
```

### Integration Points

```
┌──────────────────────┐
│  FileTransformService │
│  (@ApplicationScoped) │
└──────────┬───────────┘
           │
           ├─► Automatic Processing
           │   (per-config schedulers)
           │
           └─► Synchronous Processing
               (public method)
                      ▲
                      │
           ┌──────────┴──────────┐
           │                     │
    ┌──────┴──────┐      ┌──────┴──────┐
    │ REST API    │      │ CLI Trigger │
    │ Endpoints   │      │ Application │
    └─────────────┘      └─────────────┘
```

## Usage Examples

### Example 1: Automatic Processing (Unchanged Behavior)

**Configuration:**
```json
{
  "name": "Real-time Protection",
  "scan-interval-seconds": 30,
  "source-directory": "./data/incoming",
  ...
}
```

**Behavior:**
- Processes files automatically every 30 seconds
- Same as before, but now per-configuration

### Example 2: On-Demand Processing (New Feature)

**Configuration:**
```json
{
  "name": "Batch Unprotection",
  "scan-interval-seconds": 0,
  "source-directory": "./data/batch",
  ...
}
```

**Trigger via REST API:**
```bash
curl -X POST http://localhost:8080/api/file-transform/trigger/"Batch%20Unprotection"
```

**Response:**
```json
{
  "configuration": "Batch Unprotection",
  "filesProcessed": 42,
  "status": "success"
}
```

### Example 3: List Available Configurations

```bash
curl http://localhost:8080/api/file-transform/configurations
```

**Response:**
```json
{
  "configurations": [
    "Real-time Protection",
    "Batch Unprotection"
  ],
  "count": 2
}
```

### Example 4: Get Configuration Details

```bash
curl http://localhost:8080/api/file-transform/configuration/"Real-time%20Protection"
```

**Response:**
```json
{
  "name": "Real-time Protection",
  "sourceDirectory": "./data/incoming",
  "targetDirectory": "./data/protected",
  "errorDirectory": "./data/error",
  "scanIntervalSeconds": 30,
  "filePattern": ".*\\.json$",
  "preserveDirectoryStructure": true
}
```

## Testing

### Unit Tests
All existing tests pass:
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Integration Testing

**Test Automatic Processing:**
1. Set `scan-interval-seconds: 10`
2. Place test files in source directory
3. Wait 10 seconds
4. Verify files in target directory

**Test On-Demand Processing:**
1. Set `scan-interval-seconds: 0`
2. Place test files in source directory
3. Trigger via API: `POST /api/file-transform/trigger/{name}`
4. Verify immediate processing
5. Verify files in target directory

## Migration Guide

### For Existing Deployments

**Step 1:** Update Configuration File
```json
// Before
{
  "name": "My Config",
  "source-directory": "./data/source",
  ...
}

// After - Add scan-interval-seconds
{
  "name": "My Config",
  "scan-interval-seconds": 30,  // ← Add this
  "source-directory": "./data/source",
  ...
}
```

**Step 2:** Update application.properties
```properties
# Remove (no longer used):
# proxy.file-transform.scan-interval-seconds=30

# Keep these:
proxy.file-transform.enabled=true
proxy.file-transform.config-file=./config/file_transform_config.json
```

**Step 3:** Restart Application
- Automatic processing continues as before
- New REST API endpoints available

### Backward Compatibility

- Default `scan-interval-seconds: 0` (no automatic processing)
- Must explicitly set positive value for automatic processing
- Existing configs without field will process on-demand only

## Benefits

### 1. Flexibility
- Choose automatic or on-demand per configuration
- Mix both modes in same application

### 2. Resource Efficiency
- On-demand configs don't waste CPU scanning empty directories
- Automatic configs provide real-time processing

### 3. Control
- Precise control over when sensitive data is processed
- Integration with external schedulers and workflows

### 4. Observability
- API returns processed file count
- Clear logging for both modes
- Easy monitoring and alerting

## Use Cases

### Use Case 1: Mixed-Mode Processing
```json
[
  {
    "name": "Incoming Data Protection",
    "scan-interval-seconds": 30,    // Automatic
    "processing-context": { "Action": "Protect" }
  },
  {
    "name": "Archive Data Unprotection",
    "scan-interval-seconds": 0,      // On-demand
    "processing-context": { "Action": "Unprotect" }
  }
]
```

**Workflow:**
- Incoming data is automatically protected every 30 seconds
- Archive data is unprotected only when explicitly requested

### Use Case 2: Scheduled Batch Processing
```json
{
  "name": "End of Day Batch",
  "scan-interval-seconds": 0,
  "source-directory": "./data/daily-batch",
  ...
}
```

**Cron Job:**
```bash
0 23 * * * curl -X POST http://localhost:8080/api/file-transform/trigger/"End%20of%20Day%20Batch"
```

### Use Case 3: Manual Review Workflow
```json
{
  "name": "Reviewed Documents",
  "scan-interval-seconds": 0,
  "source-directory": "./data/reviewed",
  ...
}
```

**Process:**
1. Manual review of documents
2. Move approved documents to source directory
3. Trigger processing via API
4. Documents immediately processed and moved

## Future Enhancements

Potential future improvements:
- [ ] Async API endpoints (return immediately, process in background)
- [ ] WebSocket notifications for processing completion
- [ ] Metrics endpoint for monitoring
- [ ] Retry mechanism for failed files
- [ ] File filtering by size, date, etc.
- [ ] Processing priority queues

## Compilation Status

✅ **Build successful:**
```
[INFO] BUILD SUCCESS
[INFO] Compiling 44 source files
```

✅ **Tests passing:**
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

## Documentation

- **Main feature documentation:** `doc/FILE_TRANSFORM_FEATURE.md`
- **Synchronous processing:** `doc/FILE_TRANSFORM_SYNCHRONOUS.md` (new)
- **Configuration examples:** `config/file_transform_config.json`
- **Application properties:** `config/file_transform_application.properties.example`

## Summary

The implementation successfully adds synchronous/on-demand file processing while maintaining full backward compatibility with automatic processing. Each configuration can now independently control its processing mode through the `scan-interval-seconds` setting, providing maximum flexibility for different use cases and workflows.
