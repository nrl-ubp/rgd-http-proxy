# Quick Start: Synchronous File Processing

## What's New?

You can now process files either **automatically** (scheduled) or **on-demand** (synchronous). Each configuration controls its own behavior via the `scan-interval-seconds` setting.

## 5-Minute Setup

### 1. Enable File Transformation

Edit `application.properties`:
```properties
proxy.file-transform.enabled=true
proxy.file-transform.config-file=./config/file_transform_config.json
```

### 2. Configure Your Transformations

Edit `config/file_transform_config.json`:

**For Automatic Processing:**
```json
{
  "name": "Auto Protection",
  "scan-interval-seconds": 30,
  "source-directory": "./data/incoming",
  "target-directory": "./data/protected",
  "error-directory": "./data/error",
  ...
}
```

**For On-Demand Processing:**
```json
{
  "name": "Manual Processing",
  "scan-interval-seconds": 0,
  "source-directory": "./data/manual",
  "target-directory": "./data/processed",
  "error-directory": "./data/error",
  ...
}
```

### 3. Start the Application

```bash
./mvnw quarkus:dev
```

### 4. Use the REST API

**List configurations:**
```bash
curl http://localhost:8080/api/file-transform/configurations
```

**Trigger processing:**
```bash
curl -X POST http://localhost:8080/api/file-transform/trigger/"Manual Processing"
```

**Get details:**
```bash
curl http://localhost:8080/api/file-transform/configuration/"Manual Processing"
```

## How It Works

### Automatic Processing (`scan-interval-seconds > 0`)

```
Files appear in source directory
         ↓
  Wait N seconds (automatic)
         ↓
    Process files
         ↓
 Move to target directory
```

**Best for:** Real-time monitoring, continuous processing

### On-Demand Processing (`scan-interval-seconds ≤ 0`)

```
Files appear in source directory
         ↓
    Wait for trigger (REST API call)
         ↓
    Process files immediately
         ↓
 Move to target directory
```

**Best for:** Batch operations, controlled workflows, scheduled jobs

## Common Scenarios

### Scenario 1: Real-Time + Batch

```json
[
  {
    "name": "Real-time Incoming",
    "scan-interval-seconds": 30,
    "source-directory": "./data/realtime"
  },
  {
    "name": "End of Day Batch",
    "scan-interval-seconds": 0,
    "source-directory": "./data/batch"
  }
]
```

**Usage:**
- Real-time files processed automatically
- Batch files triggered at end of day:
  ```bash
  curl -X POST http://localhost:8080/api/file-transform/trigger/"End of Day Batch"
  ```

### Scenario 2: Protect/Unprotect Workflow

```json
[
  {
    "name": "Protect Data",
    "scan-interval-seconds": 30,
    "processing-context": { "Action": "Protect" }
  },
  {
    "name": "Unprotect Data",
    "scan-interval-seconds": 0,
    "processing-context": { "Action": "Unprotect" }
  }
]
```

**Usage:**
- Protection happens automatically
- Unprotection requires manual approval:
  ```bash
  curl -X POST http://localhost:8080/api/file-transform/trigger/"Unprotect Data"
  ```

### Scenario 3: Integration with Cron

```bash
#!/bin/bash
# daily-batch.sh - Run at 11 PM daily

curl -X POST http://localhost:8080/api/file-transform/trigger/"Daily Batch Process"

# Check exit code
if [ $? -eq 0 ]; then
    echo "Batch processing completed successfully"
else
    echo "Batch processing failed"
    exit 1
fi
```

**Crontab:**
```cron
0 23 * * * /path/to/daily-batch.sh
```

## API Response Examples

### Success Response
```json
{
  "configuration": "Manual Processing",
  "filesProcessed": 15,
  "status": "success"
}
```

### Configuration Not Found
```json
{
  "error": "Configuration not found: Invalid Name"
}
```

## Monitoring

### Check Logs

**Automatic processing:**
```
INFO  Started scheduler for 'Auto Protection' with interval: 30 seconds
INFO  Processing file: data1.json
INFO  Successfully processed file: data1.json -> ./data/protected/data1.json
```

**On-demand processing:**
```
INFO  Configuration 'Manual Processing' has scan interval 0 - will only process on-demand
INFO  Processing configuration 'Manual Processing' synchronously
INFO  Processing file: batch1.json
INFO  Successfully processed file: batch1.json -> ./data/processed/batch1.json
```

## Troubleshooting

**Q: API returns 404**
- Check configuration name (case-sensitive)
- Use `GET /api/file-transform/configurations` to list available configs

**Q: No files being processed**
- Verify `scan-interval-seconds` is set correctly
- Check source directory exists and has matching files
- Review logs for errors

**Q: Files stuck with .processing extension**
- Application crashed during processing
- Manually remove `.processing` suffix and retry

## Next Steps

- **Full Documentation:** See `doc/FILE_TRANSFORM_SYNCHRONOUS.md`
- **Configuration Reference:** See `doc/FILE_TRANSFORM_FEATURE.md`
- **Implementation Details:** See `SYNCHRONOUS_PROCESSING_IMPLEMENTATION.md`

## Support

For questions or issues:
1. Check documentation in `doc/` directory
2. Review example configuration in `config/file_transform_config.json`
3. Check application logs
4. Verify RPS engine connectivity
