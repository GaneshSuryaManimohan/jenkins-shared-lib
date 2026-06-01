def call(Map configMap) {
    pipeline {
    agent {
        label 'AGENT-1'
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        ansiColor('xterm')
    }
    
    environment{
         APP_VERSION = '' // Variable Declaration
         NEXUS_URL = pipelineGlobals.NEXUS_URL()
         region = pipelineGlobals.region()
         acc_id = pipelineGlobals.acc_id()
         component = configMap.get("component")
         project = configMap.get("project")
    }
    stages {
        stage('read the version'){
            steps{
                script{
                    def packageJson = readJSON file : 'package.json'
                    APP_VERSION = packageJson.version
                    echo "application version: ${APP_VERSION}"
                }
            }
        }
        stage('Install Dependencies') {
            steps {
                sh """
                 npm install
                 ls -lrth
                 echo "application version: ${APP_VERSION}"
                """
            }
        }
        stage('Build') {
            steps {
                sh """
                 zip -q -r ${component}-${APP_VERSION}.zip * -x Jenkinsfile -x ${component}-${APP_VERSION}.zip
                 ls -ltr
                """
            }
        }

        stage('Docker Build and Push'){
            steps{
                sh """
                    aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.${region}.amazonaws.com

                    docker build -t ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}-dev-${component}:${APP_VERSION} .

                    docker push ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}-dev-${component}:${APP_VERSION}

                """
            }
        }

        stage('Deploy'){
            steps{
                script{
                   releaseExists = sh(script: "helm list -A --short |grep -w ${component} || true", returnStdout: true).trim()
                    if(releaseExists.isEmpty()){
                        echo "${component} not found, proceeding with installation"
                        sh """
                            aws eks update-kubeconfig --region ${region} --name ${project}
                            cd helm
                            sed -i "s/IMAGE_VERSION/${APP_VERSION}/g" values.yaml
                            helm install ${component} . -n ${project}
                        """
                    }
                    else{
                        echo "${component} found, proceeding with upgrade"
                        sh """
                            aws eks update-kubeconfig --region ${region} --name ${project}
                            cd helm
                            sed -i "s/IMAGE_VERSION/${APP_VERSION}/g" values.yaml
                            helm upgrade ${component} . -n ${project}
                        """
                    }
                }
            }
        }
        stage('Verify Deployment'){
            steps{
                script{
                     rollbackStatus = sh(script: "kubectl rollout status deployment/backend -n ${project} --timeout=1m || true", returnStdout: true).trim()
                    if(rollbackStatus.contains('successfully rolled out')){
                        echo "Deployment is successfull"
                    }
                    else{
                        echo "Deployment is failed, performing rollback"
                    if(releaseExists.isEmpty()){
                    error "Deployment failed, not able to rollback, since it is first time deployment"
                }
                else{
                    sh """
                    aws eks update-kubeconfig --region ${region} --name ${project}-dev
                    helm rollback backend -n ${project} 0
                    sleep 60
                    """
                    rollbackStatus = sh(script: "kubectl rollout status deployment/backend -n expense --timeout=2m || true", returnStdout: true).trim()
                    if(rollbackStatus.contains('successfully rolled out')){
                        error "Deployment is failed, Rollback is successfull"
                    }
                    else{
                        error "Deployment is failed, Rollback is failed"
                    }
                }
            }
        }
    }
}
    //     stage('Nexus Artifact Upload') {
    //         steps {
    //             script{
    //                 nexusArtifactUploader(
    //                     nexusVersion: 'nexus3',
    //                     protocol: 'http',
    //                     nexusUrl: "${NEXUS_URL}",
    //                     groupId: 'com.expense',
    //                     version: "${APP_VERSION}",
    //                     repository: "backend",
    //                     credentialsId: 'nexus_auth',
    //                     artifacts: [
    //                         [artifactId: "backend",
    //                         classifier: '',
    //                         file: "backend-" + "${APP_VERSION}" + ".zip",
    //                         type: 'zip']
    //                     ]
    //                 )
    //             }
    //         }
    //     }
    }
    post {
        always {
            echo 'I will always say hello'
            deleteDir()
        }
        success {
            echo 'Shows Only upon success'
        }
        failure {
            echo 'shows upon failure'
        }
    }
}  

}