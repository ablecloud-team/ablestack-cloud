const fs = require('fs')
const fs2 = require('fs')

const jsonBuffer = fs.readFileSync('./public/config.json')
const dataJson = jsonBuffer.toString()
const data = JSON.parse(dataJson)

// var m = new Date()
// var dateString = m.getFullYear() + ('0' + (m.getMonth() + 1)).slice(-2) + ('0' + m.getDate()).slice(-2) //+
// ('0' + m.getHours()).slice(-2) +
// ('0' + m.getMinutes()).slice(-2) +
// ('0' + m.getSeconds()).slice(-2)

try {
  const version = fs2.readFileSync('/mnt/jenkins-work/versionInfo.txt', 'utf8')
  data.buildVersion = version
} catch (err) {
  // console.log(err)
  const version = 'Diplo-V4.5.0'
  const m = new Date()
  const date = m.getFullYear() + ('0' + (m.getMonth() + 1)).slice(-2) + ('0' + m.getDate()).slice(-2)
  data.buildVersion = version + '-' + date
}

fs.writeFileSync('./public/config.json', JSON.stringify(data))
