pipeline {
    agent any

    tools {
        jdk 'jdk-21'
        nodejs 'node-22'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {
        stage('Backend compile') {
            steps {
                sh 'mvn -B -Pdev -DskipTests clean compile'
            }
        }

        stage('Targeted tests') {
            steps {
                sh "mvn -B -Ptest '-Dtest=ApiResponseTest,GlobalExceptionHandlerTest,UsageChargeCalculatorTest,SubscriptionIntegrationTest,UsageEventIntegrationTest,InvoiceIntegrationTest,PaymentIntegrationTest,ReliabilityReconciliationIntegrationTest,DemoDatasetIntegrationTest' test"
            }
        }

        stage('Frontend build') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm run build'
                }
            }
        }

        stage('Production package') {
            steps {
                sh 'mvn -B -Pprod -DskipTests package'
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'target/*.jar,frontend/dist/**', fingerprint: false
        }
    }
}
