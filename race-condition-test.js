import http from 'k6/http';

export const options = {
  vus: 10,
  iterations: 10,
};

export default function () {
  http.post('http://localhost:8080/orders/buy/5'); // use your actual new id here
}