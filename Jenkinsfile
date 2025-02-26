pipeline {
    agent any

    stages {
        stage('Trigger Freestyle Project') {
            steps {
                script {
                    // Trigger the freestyle project
                    build job: 'ci_cd_free'
                }
            }
        }
    }
}
