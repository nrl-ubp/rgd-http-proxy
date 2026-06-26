# File Transformation - Synchronous Processing

## Overview

The file transformation feature now supports both **automatic (scheduled)** and **on-demand (synchronous)** processing modes. This allows you to choose the best approach for each configuration.

## Processing Modes

### 1. Automatic Processing (Scheduled)
Files are automatically processed at regular intervals.

**Configuration:**
```json
{
  "name": "Person Data Protection",
  "scan-interval-seconds": 30,
  ...
}
```

- **scan-interval-seconds > 0**: Process files every N seconds automatically
- Runs in background
- No manual intervention required

### 2. On-Demand Processing (Synchronous)
Files are processed only when explicitly triggered via REST API or command-line.

**Configuration:**
```json
{
  "name": "Person Data Unprotection",
  "scan-interval-seconds": 0,
  ...
}
```

- **scan-interval-seconds ≤ 0**: No automatic processing
- Process only when triggered
- Useful for batch operations or controlled workflows

## REST API Endpoints

### List All Configurations
```bash
GET /api/file-transform/configurations
```

**Example:**
```bash
curl http://localhost:8080/api/file-transform/configurations
```

**Response:**
```json
{
  "configurations": [
    "Person Data Protection",
    "Person Data Unprotection"
  ],
  "count": 2
}
```

### Get Configuration Details
```bash
GET /api/file-transform/configuration/{configName}
```

**Example:**
```bash
curl http://localhost:8080/api/file-transform/configuration/"Person%20Data%20Protection"
```

**Response:**
```json
{
  "name": "Person Data Protection",
  "sourceDirectory": "./data/source",
  "targetDirectory": "./data/target",
  "errorDirectory": "./data/error",
  "scanIntervalSeconds": 30,
  "filePattern": ".*\\.json$",
  "preserveDirectoryStructure": true
}
```

### Trigger Processing
```bash
POST /api/file-transform/trigger/{configName}
```

**Example:**
```bash
curl -X POST http://localhost:8080/api/file-transform/trigger/"Person%20Data%20Unprotection"
```

**Response:**
```json
{
  "configuration": "Person Data Unprotection",
  "filesProcessed": 5,
  "status": "success"
}
```

## Configuration Per-Config Scan Intervals

Each configuration in `file_transform_config.json` now has its own `scan-interval-seconds`:

```json
[
  {
    "name": "Automatic Processing Config",
    "scan-interval-seconds": 30,
    "source-directory": "./data/source1",
    ...
  },
  {
    "name": "On-Demand Processing Config",
    "scan-interval-seconds": 0,
    "source-directory": "./data/source2",
    ...
  },
  {
    "name": "Fast Processing Config",
    "scan-interval-seconds": 10,
    "source-directory": "./data/source3",
    ...
  }
]
```

## Use Cases

### Use Case 1: Continuous Processing
**Scenario:** Monitor a directory for incoming files and process them immediately.

**Configuration:**
```json
{
  "name": "Real-time Protection",
  "scan-interval-seconds": 10,
  "source-directory": "./data/incoming",
  ...
}
```

**Behavior:** Files are processed automatically every 10 seconds.

### Use Case 2: Batch Processing
**Scenario:** Process files only during specific time windows (e.g., end of day).

**Configuration:**
```json
{
  "name": "End of Day Batch",
  "scan-interval-seconds": 0,
  "source-directory": "./data/batch",
  ...
}
```

**Trigger via cron or scheduler:**
```bash
# Process at 11 PM daily
0 23 * * * curl -X POST http://localhost:8080/api/file-transform/trigger/"End%20of%20Day%20Batch"
```

### Use Case 3: Mixed Mode
**Scenario:** Some files process automatically, others on-demand.

**Configuration:**
```json
[
  {
    "name": "Auto Protection",
    "scan-interval-seconds": 30,
    "processing-context": { "Action": "Protect" },
    ...
  },
  {
    "name": "Manual Unprotection",
    "scan-interval-seconds": 0,
    "processing-context": { "Action": "Unprotect" },
    ...
  }
]
```

**Behavior:**
- Protection happens automatically every 30 seconds
- Unprotection requires manual trigger for control

## Integration Examples

### Shell Script Integration
```bash
#!/bin/bash
# Trigger file processing and check result

CONFIG_NAME="Person Data Protection"
API_URL="http://localhost:8080/api/file-transform/trigger"

echo "Triggering processing for: $CONFIG_NAME"

RESPONSE=$(curl -s -X POST "$API_URL/$CONFIG_NAME")
echo "Response: $RESPONSE"

# Check if successful
if echo "$RESPONSE" | grep -q '"status":"success"'; then
    echo "Processing completed successfully"
    exit 0
else
    echo "Processing failed"
    exit 1
fi
```

### Python Integration
```python
import requests
import sys

def trigger_processing(config_name):
    url = f"http://localhost:8080/api/file-transform/trigger/{config_name}"
    
    try:
        response = requests.post(url)
        response.raise_for_status()
        
        result = response.json()
        print(f"Processed {result['filesProcessed']} files")
        return result['filesProcessed']
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python trigger.py <config-name>")
        sys.exit(1)
    
    trigger_processing(sys.argv[1])
```

### Java Integration
```java
import java.net.http.*;
import java.net.URI;

public class FileTransformTrigger {
    public static void main(String[] args) throws Exception {
        String configName = args[0];
        String url = "http://localhost:8080/api/file-transform/trigger/" + configName;
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        
        HttpResponse<String> response = client.send(request, 
                HttpResponse.BodyHandlers.ofString());
        
        System.out.println("Status: " + response.statusCode());
        System.out.println("Response: " + response.body());
    }
}
```

## Monitoring and Logging

### Log Messages

**Automatic Processing:**
```
INFO  Started scheduler for 'Person Data Protection' with interval: 30 seconds
INFO  Processing file: person1.json
INFO  Successfully processed file: person1.json -> ./data/target/person1.json
```

**On-Demand Processing:**
```
INFO  Configuration 'Person Data Unprotection' has scan interval 0 - will only process on-demand
INFO  Processing configuration 'Person Data Unprotection' synchronously
INFO  Processing file: protected_data.json
INFO  Successfully processed file: protected_data.json -> ./data/unprotect-target/protected_data.json
```

### Metrics to Monitor

- **filesProcessed**: Number of files successfully processed
- **Processing time**: Time taken for each file
- **Error rate**: Files moved to error directory
- **API response time**: Time taken for REST endpoint

## Error Handling

### API Errors

**Configuration Not Found:**
```json
{
  "error": "Configuration not found: Invalid Config"
}
```
**HTTP Status:** 404

**Processing Error:**
```json
{
  "error": "Failed to transform file: Connection refused"
}
```
**HTTP Status:** 500

### Retry Strategy

For on-demand processing failures:
1. Check error directory for failed files
2. Fix the issue (connectivity, permissions, etc.)
3. Move files back to source directory
4. Trigger processing again

## Performance Considerations

### Automatic Processing
- **Pros:** No manual intervention, real-time processing
- **Cons:** Continuous resource usage, may process empty directories
- **Best for:** High-volume, time-sensitive data

### On-Demand Processing
- **Pros:** Resource efficient, controlled execution, predictable load
- **Cons:** Requires external triggering mechanism
- **Best for:** Batch processing, scheduled operations, controlled workflows

## Migration from Global Scan Interval

**Old Configuration (application.properties):**
```properties
proxy.file-transform.scan-interval-seconds=30
```

**New Configuration (file_transform_config.json):**
```json
{
  "name": "My Config",
  "scan-interval-seconds": 30,
  ...
}
```

**Migration Steps:**
1. Remove `proxy.file-transform.scan-interval-seconds` from application.properties
2. Add `scan-interval-seconds` to each configuration in file_transform_config.json
3. Set to `30` for automatic processing (like before)
4. Set to `0` for on-demand processing (new feature)

## Best Practices

1. **Use On-Demand for Production Batches**
   - Better control over processing timing
   - Easier to integrate with existing workflows
   - Predictable resource usage

2. **Use Automatic for Real-Time Monitoring**
   - Immediate processing of incoming files
   - Good for small, frequent file arrivals

3. **Combine Both Modes**
   - Protect data automatically
   - Unprotect data on-demand for security

4. **Set Reasonable Intervals**
   - Too low (< 10s): Wastes resources scanning
   - Too high (> 300s): Delays processing
   - Recommended: 30-60 seconds for most cases

5. **Monitor API Performance**
   - Track response times
   - Set up alerts for failures
   - Log all API calls for audit

## Security Considerations

- **REST API Authentication**: Consider adding authentication to prevent unauthorized triggering
- **Rate Limiting**: Implement rate limiting to prevent abuse
- **Input Validation**: Configuration names are validated before processing
- **Directory Access**: Ensure proper file system permissions

## Troubleshooting

**Q: Configuration not being scheduled**
A: Check that `scan-interval-seconds` > 0 in the config

**Q: API returns 404**
A: Verify configuration name matches exactly (case-sensitive)

**Q: Files not processing**
A: Check:
   - Source directory exists and has files
   - File pattern matches your files
   - No `.processing` files stuck (remove them)
   - RPS engine connectivity

**Q: API call hangs**
A: Processing is synchronous - it waits for completion. For large batches, this may take time.
