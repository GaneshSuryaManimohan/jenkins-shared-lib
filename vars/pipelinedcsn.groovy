#!groovy

// declaring a function

def decidePipeline(Map configMap) {
    type = configMap.get("type")
    switch(type) {
        case "nodejsEKS":
            nodejsEKS(configMap)
            break
        case "nodejsVM":
            nodejsVM(configMap)
            break
        default:
            error "Type not found"
            break
    }
}