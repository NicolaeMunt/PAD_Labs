FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /build
COPY src/ ./
RUN dotnet publish Receiver.csproj -c Release -o /out

FROM mcr.microsoft.com/dotnet/runtime:8.0
WORKDIR /app
COPY --from=build /out ./
# Default to the compose service name; any --host passed on the command line overrides it.
ENTRYPOINT ["dotnet", "Receiver.dll", "--host", "broker"]
